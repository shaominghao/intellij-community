/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.ide.bookmarks.actions;

import com.intellij.icons.AllIcons;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.bookmarks.Bookmark;
import com.intellij.ide.bookmarks.BookmarkItem;
import com.intellij.ide.bookmarks.BookmarkManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.SystemInfo;

import javax.swing.*;

class EditBookmarkDescriptionAction extends DumbAwareAction {
  private final Project myProject;
  private final JList<BookmarkItem> myList;
  private JBPopup myPopup;

  EditBookmarkDescriptionAction(Project project, JList<BookmarkItem> list) {
    super(IdeBundle.message("action.bookmark.edit.description"), IdeBundle.message("action.bookmark.edit.description.description"), AllIcons.Actions.Edit);
    setEnabledInModalContext(true);
    myProject = project;
    myList = list;
    registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(SystemInfo.isMac ? "meta ENTER" : "control ENTER")), list);
  }

  @Override
  public void update(AnActionEvent e) {
    e.getPresentation().setEnabled(myPopup != null && myPopup.isVisible() && BookmarksAction.getSelectedBookmarks(myList).size() == 1);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    if (myPopup == null || !myPopup.isVisible()) {
      return;
    }
    Bookmark bookmark = BookmarksAction.getSelectedBookmarks(myList).get(0);
   // myPopup.setUiVisible(false);

    BookmarkManager.getInstance(myProject).editDescription(bookmark, myList);

    if (myPopup != null && !myPopup.isDisposed()) {
      myPopup.setUiVisible(true);
      final JComponent content = myPopup.getContent();
      if (content != null) {
        myPopup.setSize(content.getPreferredSize());
      }
    }
  }

  public void setPopup(JBPopup popup) {
    myPopup = popup;
  }
}