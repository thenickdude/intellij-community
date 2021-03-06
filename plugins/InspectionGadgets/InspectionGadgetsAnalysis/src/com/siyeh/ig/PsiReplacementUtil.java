/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.siyeh.ig;

import com.intellij.lang.Language;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.FileTypeUtils;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.psi.util.TypeConversionUtil;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PsiReplacementUtil {
  private static final Logger LOG = Logger.getInstance(PsiReplacementUtil.class);

  /**
   * Consider to use {@link #replaceExpression(PsiExpression, String, CommentTracker)} to preserve comments
   */
  public static void replaceExpression(@NotNull PsiExpression expression, @NotNull @NonNls String newExpressionText) {
    final Project project = expression.getProject();
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final PsiElementFactory factory = psiFacade.getElementFactory();
    final PsiExpression newExpression = factory.createExpressionFromText(newExpressionText, expression);
    final PsiElement replacementExpression = expression.replace(newExpression);
    final CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
    styleManager.reformat(replacementExpression);
  }

  /**
   * @param commentTracker ensure to {@link CommentTracker#markUnchanged(PsiElement)} expressions used as getText in newExpressionText
   */
  public static void replaceExpression(@NotNull PsiExpression expression, @NotNull @NonNls String newExpressionText, CommentTracker tracker) {
    final Project project = expression.getProject();
    final PsiElement replacementExpression = tracker.replaceAndRestoreComments(expression, newExpressionText);
    CodeStyleManager.getInstance(project).reformat(replacementExpression);
  }

  public static PsiElement replaceExpressionAndShorten(@NotNull PsiExpression expression, @NotNull @NonNls String newExpressionText) {
    final Project project = expression.getProject();
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final PsiElementFactory factory = psiFacade.getElementFactory();
    final PsiExpression newExpression = factory.createExpressionFromText(newExpressionText, expression);
    final PsiElement replacementExp = expression.replace(newExpression);
    final JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
    javaCodeStyleManager.shortenClassReferences(replacementExp);
    final CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
    return styleManager.reformat(replacementExp);
  }

  /**
   * Consider to use {@link #replaceStatement(PsiExpression, String, CommentTracker)} to preserve comments
   */
  public static PsiElement replaceStatement(@NotNull PsiStatement statement, @NotNull @NonNls String newStatementText) {
    final Project project = statement.getProject();
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final PsiElementFactory factory = psiFacade.getElementFactory();
    final PsiStatement newStatement = factory.createStatementFromText(newStatementText, statement);
    final PsiElement replacementExp = statement.replace(newStatement);
    final CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
    return styleManager.reformat(replacementExp);
  }

  /**
   * @param commentTracker ensure to {@link CommentTracker#markUnchanged(PsiElement)} expressions used as getText in newStatementText
   */
  public static PsiElement replaceStatement(@NotNull PsiStatement statement, @NotNull @NonNls String newStatementText, CommentTracker commentTracker) {
    final Project project = statement.getProject();
    final PsiElement replacementExp = commentTracker.replaceAndRestoreComments(statement, newStatementText);
    final CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
    return styleManager.reformat(replacementExp);
  }

  public static void replaceStatementAndShortenClassNames(@NotNull PsiStatement statement, @NotNull @NonNls String newStatementText) {
    replaceStatementAndShortenClassNames(statement, newStatementText, null);
  }

 public static void replaceStatementAndShortenClassNames(@NotNull PsiStatement statement,
                                                         @NotNull @NonNls String newStatementText,
                                                         @Nullable CommentTracker tracker) {
    final Project project = statement.getProject();
    final CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
    final JavaCodeStyleManager javaStyleManager = JavaCodeStyleManager.getInstance(project);
    if (FileTypeUtils.isInServerPageFile(statement)) {
      final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
      final PsiFile jspFile = PsiUtilCore.getTemplateLanguageFile(statement);
      if (jspFile == null) {
        return;
      }
      final Document document = documentManager.getDocument(jspFile);
      if (document == null) {
        return;
      }
      documentManager.doPostponedOperationsAndUnblockDocument(document);
      final TextRange textRange = statement.getTextRange();
      document.replaceString(textRange.getStartOffset(), textRange.getEndOffset(), newStatementText);
      documentManager.commitDocument(document);
      final FileViewProvider viewProvider = jspFile.getViewProvider();
      PsiElement elementAt = viewProvider.findElementAt(textRange.getStartOffset(), JavaLanguage.INSTANCE);
      if (elementAt == null) {
        return;
      }
      final int endOffset = textRange.getStartOffset() + newStatementText.length();
      while (elementAt.getTextRange().getEndOffset() < endOffset || !(elementAt instanceof PsiStatement)) {
        elementAt = elementAt.getParent();
        if (elementAt == null) {
          LOG.error("Cannot decode statement");
          return;
        }
      }
      final PsiStatement newStatement = (PsiStatement)elementAt;
      javaStyleManager.shortenClassReferences(newStatement);
      final TextRange newTextRange = newStatement.getTextRange();
      final Language baseLanguage = viewProvider.getBaseLanguage();
      final PsiFile element = viewProvider.getPsi(baseLanguage);
      if (element != null) {
        styleManager.reformatRange(element, newTextRange.getStartOffset(), newTextRange.getEndOffset());
      }
    }
    else {
      PsiStatement newStatement;
      if (tracker != null) {
        newStatement = (PsiStatement)tracker.replaceAndRestoreComments(statement, newStatementText);
      }
      else {
        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        newStatement = (PsiStatement)statement.replace(factory.createStatementFromText(newStatementText, statement));
      }
      javaStyleManager.shortenClassReferences(newStatement);
      styleManager.reformat(newStatement);
    }
  }

  public static void replaceExpressionWithReferenceTo(@NotNull PsiExpression expression, @NotNull PsiMember target) {
    final Project project = expression.getProject();
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final PsiElementFactory factory = psiFacade.getElementFactory();
    final PsiReferenceExpression newExpression = (PsiReferenceExpression)factory.createExpressionFromText("xxx", expression);
    final PsiReferenceExpression replacementExpression = (PsiReferenceExpression)expression.replace(newExpression);
    final PsiElement element = replacementExpression.bindToElement(target);
    final JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
    styleManager.shortenClassReferences(element);
  }

  @NotNull
  public static String getElementText(@NotNull PsiElement element, @Nullable PsiElement elementToReplace, @Nullable String replacement) {
    final StringBuilder out = new StringBuilder();
    getElementText(element, elementToReplace, replacement, out);
    return out.toString();
  }

  private static void getElementText(@NotNull PsiElement element, @Nullable PsiElement elementToReplace,
                                     @Nullable String replacement, @NotNull StringBuilder out) {
    if (element.equals(elementToReplace)) {
      out.append(replacement);
      return;
    }
    final PsiElement[] children = element.getChildren();
    if (children.length == 0) {
      out.append(element.getText());
      return;
    }
    for (PsiElement child : children) {
      getElementText(child, elementToReplace, replacement, out);
    }
  }

  public static void replaceOperatorAssignmentWithAssignmentExpression(@NotNull PsiAssignmentExpression assignmentExpression) {
    CommentTracker tracker = new CommentTracker();
    final PsiJavaToken sign = assignmentExpression.getOperationSign();
    final PsiExpression lhs = assignmentExpression.getLExpression();
    final PsiExpression rhs = assignmentExpression.getRExpression();
    final String operator = sign.getText();
    final String newOperator = operator.substring(0, operator.length() - 1);
    final String lhsText = tracker.markUnchanged(lhs).getText();
    final String rhsText = (rhs == null) ? "" : tracker.markUnchanged(rhs).getText();
    final boolean parentheses = ParenthesesUtils.areParenthesesNeeded(sign, rhs);
    final String cast = getCastString(lhs, rhs);
    final StringBuilder newExpression = new StringBuilder(lhsText);
    newExpression.append('=').append(cast);
    if (!cast.isEmpty()) {
      newExpression.append('(');
    }
    newExpression.append(lhsText).append(newOperator);
    if (parentheses) {
      newExpression.append('(').append(rhsText).append(')');
    }
    else {
      newExpression.append(rhsText);
    }
    if (!cast.isEmpty()) {
      newExpression.append(')');
    }
    replaceExpression(assignmentExpression, newExpression.toString(), tracker);
  }

  private static String getCastString(PsiExpression lhs, PsiExpression rhs) {
    if (lhs == null || rhs == null) {
      return "";
    }
    final PsiType lType = lhs.getType();
    PsiType rType = rhs.getType();
    if (TypeConversionUtil.isNumericType(rType)) {
      rType = TypeConversionUtil.binaryNumericPromotion(lType, rType);
    }
    if (lType == null || rType == null ||
        TypeConversionUtil.isAssignable(lType, rType) || !TypeConversionUtil.areTypesConvertible(lType, rType)) {
      return "";
    }
    return '(' + lType.getCanonicalText() + ')';
  }
}
