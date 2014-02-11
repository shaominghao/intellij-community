/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.configuration;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModel;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.projectRoots.impl.SdkListCellRenderer;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.NullableConsumer;
import com.intellij.util.ui.UIUtil;
import com.intellij.webcore.packaging.PackagesNotificationPanel;
import com.jetbrains.python.packaging.ui.PyInstalledPackagesPanel;
import com.jetbrains.python.packaging.ui.PyPackageManagementService;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.sdk.*;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;
import icons.PythonIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

public class PyActiveSdkConfigurable implements UnnamedConfigurable {
  private JPanel myPanel;
  private final Project myProject;
  @Nullable private final Module myModule;
  private MyListener myListener;

  private PyConfigurableInterpreterList myInterpreterList;
  private ProjectSdksModel myProjectSdksModel;
  private ComboBox mySdkCombo;
  private PyInstalledPackagesPanel myPackagesPanel;
  private JButton myDetailsButton;
  private static final String SHOW_ALL = "Show All";

  public PyActiveSdkConfigurable(@NotNull Project project) {
    myModule = null;
    myProject = project;
    layoutPanel();
    initContent();
  }

  public PyActiveSdkConfigurable(@NotNull Module module) {
    myModule = module;
    myProject = module.getProject();
    layoutPanel();
    initContent();
  }

  private void initContent() {
    myInterpreterList = PyConfigurableInterpreterList.getInstance(myProject);
    myInterpreterList.setSdkCombo(mySdkCombo);

    myProjectSdksModel = myInterpreterList.getModel();
    myListener = new MyListener(this);
    myProjectSdksModel.addListener(myListener);

    mySdkCombo.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        final Sdk selectedSdk = (Sdk)mySdkCombo.getSelectedItem();
        if (selectedSdk != null)
          myPackagesPanel.updatePackages(new PyPackageManagementService(myProject, selectedSdk));
      }
    });

    myDetailsButton.addActionListener(new ActionListener() {
                                        @Override
                                        public void actionPerformed(ActionEvent e) {
                                          DetailsChooser.show(myProject, myProjectSdksModel.getSdks(), new PythonSdkConfigurable(myProject).createComponent(),
                                                              RelativePoint.fromScreen(myDetailsButton.getLocationOnScreen()), true,
                                                              new NullableConsumer<Sdk>() {
                                                                @Override
                                                                public void consume(Sdk sdk) {
                                                                  if (sdk == null) return;
                                                                  final Sdk existedSdk = myProjectSdksModel.findSdk(sdk.getName());
                                                                  if (existedSdk == null) {
                                                                    myProjectSdksModel.addSdk(sdk);
                                                                  }
                                                                  myInterpreterList.setSelectedSdk(sdk);

                                                                  mySdkCombo.getModel().setSelectedItem(sdk);
                                                                  myPackagesPanel.updatePackages(
                                                                    new PyPackageManagementService(myProject, sdk));
                                                                }
                                                              }
                                          );
                                        }
                                      }
    );

  }

  private void layoutPanel() {
    final GridBagLayout layout = new GridBagLayout();
    myPanel = new JPanel(layout);
    final JLabel label = new JLabel("Project Interpreter:");
    final JLabel label1 = new JLabel("  ");
    mySdkCombo = new ComboBox() {
      @Override
      public void setSelectedItem(Object item)
      {
        if (SHOW_ALL.equals(item)) {
          DialogBuilder dialog = new DialogBuilder(myProject);
          dialog.setTitle("Python Interpreters");
          dialog.setCenterPanel(new PythonSdkConfigurable(myProject).createComponent());
          dialog.show();
          return;
        }
        if (!PySdkListCellRenderer.SEPARATOR.equals(item.toString()))
          super.setSelectedItem(item);
      }
    };
    mySdkCombo.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
    mySdkCombo.setRenderer(new SdkListCellRenderer("<None>"));

    PackagesNotificationPanel notificationsArea = new PackagesNotificationPanel(myProject);
    final JComponent notificationsComponent = notificationsArea.getComponent();
    notificationsComponent.setPreferredSize(new Dimension(500, 29));

    myDetailsButton = new JButton();
    myDetailsButton.setIcon(PythonIcons.Python.InterpreterGear);
    myDetailsButton.setIconTextGap(0);
    myDetailsButton.setPreferredSize(new Dimension(29, 29));
    if (((UIUtil.isUnderAquaLookAndFeel())) || UIUtil.isUnderIntelliJLaF() || UIUtil.isUnderDarcula()) {
      myDetailsButton.putClientProperty("JButton.buttonType", "square");
    }

    myPackagesPanel = new PyInstalledPackagesPanel(myProject, notificationsArea);
    mySdkCombo.setPreferredSize(new Dimension(250, 29));
    final GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(2,2,2,2);

    c.gridx = 0;
    c.gridy = 0;
    myPanel.add(label, c);

    c.gridx = 1;
    c.gridy = 0;
    c.weightx = 0.1;
    myPanel.add(mySdkCombo, c);

    c.insets = new Insets(0,5,0,2);
    c.gridx = 2;
    c.gridy = 0;
    c.weightx = 0.0;
    myPanel.add(myDetailsButton, c);

    c.insets = new Insets(2,2,2,2);
    c.gridx = 0;
    c.gridy = 1;
    c.gridwidth = 3;
    myPanel.add(label1, c);

    c.gridx = 0;
    c.gridy = 2;
    c.weighty = 0.5;
    c.gridwidth = 3;
    c.gridheight = GridBagConstraints.RELATIVE;
    c.fill = GridBagConstraints.BOTH;
    myPanel.add(myPackagesPanel, c);

    c.gridheight = GridBagConstraints.REMAINDER;
    c.gridx = 0;
    c.gridy = 3;
    c.gridwidth = 3;

    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.SOUTH;

    myPanel.add(notificationsComponent, c);
  }

  @Override
  public JComponent createComponent() {
    return myPanel;
  }

  @Override
  public boolean isModified() {
    final Sdk sdk = getSdk();
    final Sdk selectedItem = (Sdk)mySdkCombo.getSelectedItem();
    return selectedItem instanceof PyDetectedSdk || sdk != myProjectSdksModel.findSdk(selectedItem);
  }

  @Nullable
  private Sdk getSdk() {
    if (myModule == null) {
      return ProjectRootManager.getInstance(myProject).getProjectSdk();
    }
    final ModuleRootManager rootManager = ModuleRootManager.getInstance(myModule);
    return rootManager.getSdk();
  }

  @Override
  public void apply() throws ConfigurationException {
    final Sdk item = (Sdk)mySdkCombo.getSelectedItem();
    if (item instanceof PyDetectedSdk) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          final Sdk sdk = SdkConfigurationUtil.createAndAddSDK(item.getName(), PythonSdkType.getInstance());
          SdkConfigurationUtil.setDirectoryProjectSdk(myProject, sdk);
        }
      }, ModalityState.any());
    }
    myProjectSdksModel.apply();
    final Sdk selectedSdk = myProjectSdksModel.findSdk(item);
    if (myModule == null) {
      final ProjectRootManager rootManager = ProjectRootManager.getInstance(myProject);
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        @Override
        public void run() {
          rootManager.setProjectSdk(selectedSdk);
        }
      });
    }
    else {
      ModuleRootModificationUtil.setModuleSdk(myModule, selectedSdk);
    }
    final Sdk prevSdk = ProjectRootManager.getInstance(myProject).getProjectSdk();
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        ProjectRootManager.getInstance(myProject).setProjectSdk(selectedSdk);
      }
    });

    myProjectSdksModel.setProjectSdk(selectedSdk);

    // update string literals if different LanguageLevel was selected
    if (prevSdk != null && selectedSdk != null) {
      final PythonSdkFlavor flavor1 = PythonSdkFlavor.getFlavor(selectedSdk);
      final PythonSdkFlavor flavor2 = PythonSdkFlavor.getFlavor(prevSdk);
      if (flavor1 != null && flavor2 != null) {
        final LanguageLevel languageLevel1 = flavor1.getLanguageLevel(selectedSdk);
        final LanguageLevel languageLevel2 = flavor2.getLanguageLevel(prevSdk);
        if ((languageLevel1.isPy3K() && languageLevel2.isPy3K()) ||
            (!languageLevel1.isPy3K()) && !languageLevel2.isPy3K()) {
          return;
        }
      }
    }
    rehighlightStrings(myProject);
  }

  public static void rehighlightStrings(final @NotNull Project project) {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {

        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
          if (editor instanceof EditorEx && editor.getProject() == project) {
            final VirtualFile vFile = ((EditorEx)editor).getVirtualFile();
            if (vFile != null) {
              final EditorHighlighter highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, vFile);
              ((EditorEx)editor).setHighlighter(highlighter);
            }
          }
        }
      }
    });
  }

  @Override
  public void reset() {
    updateSdkList(false);

    final Sdk sdk = getSdk();
    mySdkCombo.setSelectedItem(myProjectSdksModel.getProjectSdks().get(sdk));
  }

  private void updateSdkList(boolean preserveSelection) {
    final List<Sdk> sdkList = myInterpreterList.getAllPythonSdks(myProject);
    Sdk selection = preserveSelection ? (Sdk)mySdkCombo.getSelectedItem() : null;
    if (!sdkList.contains(selection)) {
      selection = null;
    }
    VirtualEnvProjectFilter.removeNotMatching(myProject, sdkList);
    // if the selection is a non-matching virtualenv, show it anyway
    if (selection != null && !sdkList.contains(selection)) {
      sdkList.add(0, selection);
    }
    List<Object> items = new ArrayList<Object>();
    items.add(null);

    boolean remoteSeparator = true;
    boolean separator = true;
    boolean detectedSeparator = true;
    boolean hasAssociationsWithDifferentProject = false;
    final String projectBasePath = myProject.getBasePath();
    for (Sdk sdk : sdkList) {
      if (!PythonSdkType.isVirtualEnv(sdk) && !PythonSdkType.isRemote(sdk) &&
          !(sdk instanceof PyDetectedSdk) && separator) {
        items.add(PySdkListCellRenderer.SEPARATOR);
        separator = false;
      }
      if (PythonSdkType.isRemote(sdk) && remoteSeparator) {
        items.add(PySdkListCellRenderer.SEPARATOR);
        remoteSeparator = false;
      }
      if (sdk instanceof PyDetectedSdk && detectedSeparator) {
        items.add(PySdkListCellRenderer.SEPARATOR);
        detectedSeparator = false;
      }
      items.add(sdk);

      final PythonSdkAdditionalData data = (PythonSdkAdditionalData)sdk.getSdkAdditionalData();
      if (data != null) {
        final String path = data.getAssociatedProjectPath();
        if (path != null && !path.equals(projectBasePath)) hasAssociationsWithDifferentProject = true;
      }
    }

    if (hasAssociationsWithDifferentProject) {
      items.add(PySdkListCellRenderer.SEPARATOR);
      items.add(SHOW_ALL);
    }

    mySdkCombo.setRenderer(new PySdkListCellRenderer());
    mySdkCombo.setModel(new CollectionComboBoxModel(items, selection));
  }

  @Override
  public void disposeUIResources() {
    myProjectSdksModel.removeListener(myListener);
    myInterpreterList.disposeModel();
  }

  private static class MyListener implements SdkModel.Listener {
    private final PyActiveSdkConfigurable myConfigurable;

    public MyListener(PyActiveSdkConfigurable configurable) {
      myConfigurable = configurable;
    }

    @Override
    public void sdkAdded(Sdk sdk) {
      myConfigurable.reset();
    }

    @Override
    public void beforeSdkRemove(Sdk sdk) {
      myConfigurable.updateSdkList(true);
    }

    @Override
    public void sdkChanged(Sdk sdk, String previousName) {
      myConfigurable.updateSdkList(true);
    }

    @Override
    public void sdkHomeSelected(Sdk sdk, String newSdkHome) {
    }
  }
}
