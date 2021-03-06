/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.jetbrains.python.inspections

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.util.containers.ContainerUtil
import com.jetbrains.python.codeInsight.stdlib.DATACLASSES_INITVAR_TYPE
import com.jetbrains.python.codeInsight.stdlib.DUNDER_POST_INIT
import com.jetbrains.python.codeInsight.stdlib.DataclassParameters
import com.jetbrains.python.codeInsight.stdlib.parseDataclassParameters
import com.jetbrains.python.codeInsight.typing.PyTypingTypeProvider
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.impl.PyCallExpressionHelper
import com.jetbrains.python.psi.impl.stubs.PyDataclassFieldStubImpl
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.*

class PyDataclassInspection : PyInspection() {

  companion object {
    private val ORDER_OPERATORS = setOf("__lt__", "__le__", "__gt__", "__ge__")
    private val DATACLASSES_HELPERS = setOf("dataclasses.fields", "dataclasses.asdict", "dataclasses.astuple", "dataclasses.replace")
  }

  override fun buildVisitor(holder: ProblemsHolder,
                            isOnTheFly: Boolean,
                            session: LocalInspectionToolSession): PsiElementVisitor = Visitor(holder, session)

  private class Visitor(holder: ProblemsHolder, session: LocalInspectionToolSession) : PyInspectionVisitor(holder, session) {

    override fun visitPyTargetExpression(node: PyTargetExpression?) {
      super.visitPyTargetExpression(node)

      val cls = getInstancePyClass(node?.qualifier) ?: return
      if (parseDataclassParameters(cls, myTypeEvalContext)?.frozen == true) {
        registerProblem(node, "'${cls.name}' object attribute '${node!!.name}' is read-only", ProblemHighlightType.GENERIC_ERROR)
      }
    }

    override fun visitPyClass(node: PyClass?) {
      super.visitPyClass(node)

      if (node != null) {
        val dataclassParameters = parseDataclassParameters(node, myTypeEvalContext)

        if (dataclassParameters != null) {
          processDataclassParameters(dataclassParameters)

          val postInit = node.findMethodByName(DUNDER_POST_INIT, false, myTypeEvalContext)
          val initVars = mutableListOf<PyTargetExpression>()

          node.processClassLevelDeclarations { element, _ ->
            if (element is PyTargetExpression && !PyTypingTypeProvider.isClassVar(element, myTypeEvalContext)) {
              processDefaultFieldValue(element)
              processAsInitVar(element, postInit)?.let { initVars.add(it) }
              processFieldFunctionCall(element)
            }

            true
          }

          if (postInit != null) {
            processPostInitDefinition(postInit, dataclassParameters, initVars)
          }

          PyNamedTupleInspection.inspectFieldsOrder(
            node,
            this::registerProblem,
            { !PyTypingTypeProvider.isClassVar(it, myTypeEvalContext) },
            {
              val fieldStub = PyDataclassFieldStubImpl.create(it)
              if (fieldStub != null) {
                fieldStub.hasDefault() || fieldStub.hasDefaultFactory()
              }
              else {
                it.hasAssignedValue()
              }
            }
          )
        }
      }
    }

    override fun visitPyBinaryExpression(node: PyBinaryExpression?) {
      super.visitPyBinaryExpression(node)

      if (node != null && ORDER_OPERATORS.contains(node.referencedName)) {
        val leftClass = getInstancePyClass(node.leftExpression) ?: return
        val rightClass = getInstancePyClass(node.rightExpression) ?: return

        val leftDataclassParameters = parseDataclassParameters(leftClass, myTypeEvalContext)

        if (leftClass != rightClass &&
            leftDataclassParameters != null &&
            parseDataclassParameters(rightClass, myTypeEvalContext) != null) {
          registerProblem(node.psiOperator,
                          "'${node.referencedName}' not supported between instances of '${leftClass.name}' and '${rightClass.name}'",
                          ProblemHighlightType.GENERIC_ERROR)
        }

        if (leftClass == rightClass && leftDataclassParameters?.order == false) {
          registerProblem(node.psiOperator,
                          "'${node.referencedName}' not supported between instances of '${leftClass.name}'",
                          ProblemHighlightType.GENERIC_ERROR)
        }
      }
    }

    override fun visitPyCallExpression(node: PyCallExpression?) {
      super.visitPyCallExpression(node)

      if (node != null) {
        val resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(myTypeEvalContext)
        val markedCallee = node.multiResolveCallee(resolveContext).singleOrNull()
        val callee = markedCallee?.element
        val calleeQName = callee?.qualifiedName

        if (markedCallee != null && callee != null && DATACLASSES_HELPERS.contains(calleeQName)) {
          val mapping = PyCallExpressionHelper.mapArguments(node, markedCallee, myTypeEvalContext)

          val dataclassParameter = callee.getParameters(myTypeEvalContext).firstOrNull()
          val dataclassArgument = mapping.mappedParameters.entries.firstOrNull { it.value == dataclassParameter }?.key

          processHelperDataclassArgument(dataclassArgument, calleeQName!!)
        }
      }
    }

    override fun visitPyReferenceExpression(node: PyReferenceExpression?) {
      super.visitPyReferenceExpression(node)

      if (node != null && node.isQualified) {
        val cls = getInstancePyClass(node.qualifier) ?: return

        if (parseDataclassParameters(cls, myTypeEvalContext) != null) {
          cls.processClassLevelDeclarations { element, _ ->
            if (element is PyTargetExpression && element.name == node.name) {
              val type = myTypeEvalContext.getType(element)

              if (type is PyClassType && type.classQName == DATACLASSES_INITVAR_TYPE) {
                registerProblem(node.lastChild,
                                "'${cls.name}' object could have no attribute '${element.name}' because it is declared as init-only",
                                ProblemHighlightType.GENERIC_ERROR_OR_WARNING)

                return@processClassLevelDeclarations false
              }
            }

            true
          }
        }
      }
    }

    private fun getInstancePyClass(element: PyTypedElement?): PyClass? {
      val type = element?.let { myTypeEvalContext.getType(it) } as? PyClassType
      return if (type != null && !type.isDefinition) type.pyClass else null
    }

    private fun processDataclassParameters(dataclassParameters: DataclassParameters) {
      if (!dataclassParameters.eq && dataclassParameters.order) {
        val eqArgument = dataclassParameters.eqArgument
        if (eqArgument != null) {
          registerProblem(eqArgument, "'eq' must be true if 'order' is true", ProblemHighlightType.GENERIC_ERROR)
        }
      }
    }

    private fun processDefaultFieldValue(field: PyTargetExpression) {
      val value = field.findAssignedValue()
      val valueClass = getInstancePyClass(value)

      if (valueClass != null) {
        val builtinCache = PyBuiltinCache.getInstance(field)

        if (valueClass == builtinCache.listType?.pyClass ||
            valueClass == builtinCache.setType?.pyClass ||
            valueClass == builtinCache.tupleType?.pyClass) {
          registerProblem(value,
                          "Mutable default '${valueClass.name}' is not allowed",
                          ProblemHighlightType.GENERIC_ERROR)
        }
      }
    }

    private fun processAsInitVar(field: PyTargetExpression, postInit: PyFunction?): PyTargetExpression? {
      val type = myTypeEvalContext.getType(field)

      if (type is PyClassType && type.classQName == DATACLASSES_INITVAR_TYPE) {
        if (postInit == null) {
          registerProblem(field,
                          "Attribute '${field.name}' is useless until '${DUNDER_POST_INIT}' is declared",
                          ProblemHighlightType.LIKE_UNUSED_SYMBOL)
        }

        return field
      }

      return null
    }

    private fun processFieldFunctionCall(field: PyTargetExpression) {
      val fieldStub = PyDataclassFieldStubImpl.create(field)
      if (fieldStub != null && fieldStub.hasDefault() && fieldStub.hasDefaultFactory()) {
        val call = field.findAssignedValue() as? PyCallExpression ?: return

        registerProblem(call.argumentList, "Cannot specify both 'default' and 'default_factory'", ProblemHighlightType.GENERIC_ERROR)
      }
    }

    private fun processPostInitDefinition(postInit: PyFunction,
                                          dataclassParameters: DataclassParameters,
                                          initVars: List<PyTargetExpression>) {
      if (!dataclassParameters.init) {
        registerProblem(postInit.nameIdentifier,
                        "'${DUNDER_POST_INIT}' would not be called until 'init' parameter is set to True",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL)
      }

      val parameters = ContainerUtil.subList(postInit.getParameters(myTypeEvalContext), 1)
      val message = "'${DUNDER_POST_INIT}' should take all init-only variables in the same order as they are defined"

      if (parameters.size != initVars.size) {
        registerProblem(postInit.parameterList, message, ProblemHighlightType.GENERIC_ERROR)
      }
      else {
        parameters
          .asSequence()
          .zip(initVars.asSequence())
          .all { it.first.name == it.second.name }
          .also { if (!it) registerProblem(postInit.parameterList, message) }
      }
    }

    private fun processHelperDataclassArgument(argument: PyExpression?, calleeQName: String) {
      if (argument == null) return

      val allowDefinition = calleeQName == "dataclasses.fields"

      if (isNotDataclass(myTypeEvalContext.getType(argument), allowDefinition)) {
        val message = "'$calleeQName' method should be called on dataclass instances" + if (allowDefinition) " or types" else ""

        registerProblem(argument, message)
      }
    }

    private fun isNotDataclass(type: PyType?, allowDefinition: Boolean): Boolean {
      if (type is PyStructuralType || PyTypeChecker.isUnknown(type, myTypeEvalContext)) return false
      if (type is PyUnionType) return type.members.all { isNotDataclass(it, allowDefinition) }

      return type !is PyClassType ||
             !allowDefinition && type.isDefinition ||
             parseDataclassParameters(type.pyClass, myTypeEvalContext) == null
    }
  }
}