// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.lang;

import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.impl.FilePropertyPusher;
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.NonPhysicalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashMap;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.*;

/**
 * @author gregsh
 */
public abstract class PerFileMappingsBase<T> implements PersistentStateComponent<Element>, PerFileMappings<T> {
  private final Map<VirtualFile, T> myMappings = ContainerUtil.newHashMap();

  @Nullable
  protected FilePropertyPusher<T> getFilePropertyPusher() {
    return null;
  }

  @Nullable
  protected Project getProject() { return null; }

  @NotNull
  @Override
  public Map<VirtualFile, T> getMappings() {
    synchronized (myMappings) {
      cleanup();
      return Collections.unmodifiableMap(myMappings);
    }
  }

  private void cleanup() {
    for (Iterator<VirtualFile> i = myMappings.keySet().iterator(); i.hasNext();) {
      VirtualFile file = i.next();
      if (file != null /* PROJECT, top-level */ && !file.isValid()) {
        i.remove();
      }
    }
  }

  @Override
  @Nullable
  public T getMapping(@Nullable VirtualFile file) {
    T t = getConfiguredMapping(file);
    return t == null? getDefaultMapping(file) : t;
  }

  @Nullable
  public T getConfiguredMapping(@Nullable VirtualFile file) {
    FilePropertyPusher<T> pusher = getFilePropertyPusher();
    return getMappingInner(file, myMappings, pusher == null ? null : pusher.getFileDataKey());
  }

  @Nullable
  protected T getMappingInner(@Nullable VirtualFile file, @Nullable Map<VirtualFile, T> mappings, @Nullable Key<T> pusherKey) {
    if (file instanceof VirtualFileWindow) {
      final VirtualFileWindow window = (VirtualFileWindow)file;
      file = window.getDelegate();
    }
    VirtualFile originalFile = file instanceof LightVirtualFile ? ((LightVirtualFile)file).getOriginalFile() : null;
    if (Comparing.equal(originalFile, file)) originalFile = null;

    if (file != null) {
      final T pushedValue = pusherKey == null? null : file.getUserData(pusherKey);
      if (pushedValue != null) return pushedValue;
    }
    if (originalFile != null) {
      final T pushedValue = pusherKey == null? null : originalFile.getUserData(pusherKey);
      if (pushedValue != null) return pushedValue;
    }
    if (mappings == null) return null;
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (mappings) {
      T t = getMappingForHierarchy(file, mappings);
      if (t != null) return t;
      t = getMappingForHierarchy(originalFile, mappings);
      if (t != null) return t;
      return getNotInHierarchy(file, mappings);
    }
  }

  @Nullable
  protected T getNotInHierarchy(@Nullable VirtualFile file, @NotNull Map<VirtualFile, T> mappings) {
    if (getProject() == null || file == null ||
        file.getFileSystem() instanceof NonPhysicalFileSystem ||
        ProjectFileIndex.getInstance(getProject()).isInContent(file)) {
      return mappings.get(null);
    }
    return null;
  }

  private static <T> T getMappingForHierarchy(@Nullable VirtualFile file, @NotNull Map<VirtualFile, T> mappings) {
    for (VirtualFile cur = file; cur != null; cur = cur.getParent()) {
      T t = mappings.get(cur);
      if (t != null) return t;
    }
    return null;
  }

  @Override
  @Nullable
  public T getDefaultMapping(@Nullable VirtualFile file) {
    return null;
  }

  @Nullable
  public T getImmediateMapping(@Nullable VirtualFile file) {
    synchronized (myMappings) {
      return myMappings.get(file);
    }
  }

  @Override
  public void setMappings(@NotNull final Map<VirtualFile, T> mappings) {
    Collection<VirtualFile> oldFiles;
    synchronized (myMappings) {
      oldFiles = ContainerUtil.newArrayList(myMappings.keySet());
      myMappings.clear();
      myMappings.putAll(mappings);
      cleanup();
    }
    Project project = getProject();
    handleMappingChange(mappings.keySet(), oldFiles, project != null && !project.isDefault());
  }

  public void setMapping(@Nullable final VirtualFile file, @Nullable T dialect) {
    synchronized (myMappings) {
      if (dialect == null) {
        myMappings.remove(file);
      }
      else {
        myMappings.put(file, dialect);
      }
    }
    List<VirtualFile> files = ContainerUtil.createMaybeSingletonList(file);
    handleMappingChange(files, files, false);
  }

  private void handleMappingChange(Collection<VirtualFile> files, Collection<VirtualFile> oldFiles, boolean includeOpenFiles) {
    Project project = getProject();
    FilePropertyPusher<T> pusher = getFilePropertyPusher();
    if (project != null && pusher != null) {
      for (VirtualFile oldFile : oldFiles) {
        if (oldFile == null) continue; // project
        oldFile.putUserData(pusher.getFileDataKey(), null);
      }
      if (!project.isDefault()) {
        PushedFilePropertiesUpdater.getInstance(project).pushAll(pusher);
      }
    }
    if (shouldReparseFiles()) {
      Project[] projects = project == null ? ProjectManager.getInstance().getOpenProjects() : new Project[] { project };
      for (Project p : projects) {
        PsiDocumentManager.getInstance(p).reparseFiles(files, includeOpenFiles);
      }
    }
  }

  public abstract List<T> getAvailableValues();

  @Nullable
  protected abstract String serialize(T t);

  @Override
  public Element getState() {
    synchronized (myMappings) {
      cleanup();
      final Element element = new Element("x");
      final List<VirtualFile> files = new ArrayList<>(myMappings.keySet());
      Collections.sort(files, (o1, o2) -> {
        if (o1 == null || o2 == null) return o1 == null ? o2 == null ? 0 : 1 : -1;
        return o1.getPath().compareTo(o2.getPath());
      });
      for (VirtualFile file : files) {
        final T dialect = myMappings.get(file);
        String value = serialize(dialect);
        if (value != null) {
          final Element child = new Element("file");
          element.addContent(child);
          child.setAttribute("url", file == null ? "PROJECT" : file.getUrl());
          child.setAttribute(getValueAttribute(), value);
        }
      }
      return element;
    }
  }

  @Nullable
  protected T handleUnknownMapping(VirtualFile file, String value) {
    return null;
  }

  @NotNull
  protected String getValueAttribute() {
    return "value";
  }

  @Override
  public void loadState(@NotNull final Element state) {
    synchronized (myMappings) {
      final THashMap<String, T> dialectMap = new THashMap<>();
      for (T dialect : getAvailableValues()) {
        String key = serialize(dialect);
        if (key != null) {
          dialectMap.put(key, dialect);
        }
      }
      myMappings.clear();
      final List<Element> files = state.getChildren("file");
      for (Element fileElement : files) {
        final String url = fileElement.getAttributeValue("url");
        final String dialectID = fileElement.getAttributeValue(getValueAttribute());
        final VirtualFile file = url.equals("PROJECT") ? null : VirtualFileManager.getInstance().findFileByUrl(url);
        T dialect = dialectMap.get(dialectID);
        if (dialect == null) {
          dialect = handleUnknownMapping(file, dialectID);
          if (dialect == null) continue;
        }
        if (file != null || url.equals("PROJECT")) {
          myMappings.put(file, dialect);
        }
      }
    }
  }

  @TestOnly
  public void cleanupForNextTest() {
    synchronized (myMappings) {
      myMappings.clear();
    }
  }

  protected boolean shouldReparseFiles() {
    return true;
  }

  public boolean hasMappings() {
    synchronized (myMappings) {
      return !myMappings.isEmpty();
    }
  }
}
