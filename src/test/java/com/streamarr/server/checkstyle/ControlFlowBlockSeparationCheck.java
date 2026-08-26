package com.streamarr.server.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.util.stream.IntStream;

/**
 * Enforces the CLAUDE.md "Flat Control Flow" rule that a completed control-flow block ({@code if},
 * {@code for}, {@code while}, {@code do}, {@code switch}, {@code try}, {@code synchronized}) is
 * followed by a blank line when another statement follows it in the same body. Comment lines are
 * not separation; nothing is required before {@code else}, {@code catch}, {@code finally}, a {@code
 * do}/{@code while} tail, or the enclosing closing brace.
 */
public final class ControlFlowBlockSeparationCheck extends AbstractCheck {

  public static final String MSG_SEPARATION = "control.flow.block.separation";

  private static final int[] CONTROL_FLOW_TOKENS = {
    TokenTypes.LITERAL_IF,
    TokenTypes.LITERAL_FOR,
    TokenTypes.LITERAL_WHILE,
    TokenTypes.LITERAL_DO,
    TokenTypes.LITERAL_SWITCH,
    TokenTypes.LITERAL_TRY,
    TokenTypes.LITERAL_SYNCHRONIZED
  };

  @Override
  public int[] getDefaultTokens() {
    return CONTROL_FLOW_TOKENS.clone();
  }

  @Override
  public int[] getAcceptableTokens() {
    return CONTROL_FLOW_TOKENS.clone();
  }

  @Override
  public int[] getRequiredTokens() {
    return new int[0];
  }

  @Override
  public void visitToken(DetailAST controlFlow) {
    if (controlFlow.getParent().getType() != TokenTypes.SLIST) {
      return;
    }

    var followingStatement = controlFlow.getNextSibling();
    if (followingStatement == null || followingStatement.getType() == TokenTypes.RCURLY) {
      return;
    }

    var statementLine = firstLineOf(followingStatement);
    if (hasBlankLineBetween(lastDescendantOf(controlFlow).getLineNo(), statementLine)) {
      return;
    }

    log(statementLine, MSG_SEPARATION);
  }

  private boolean hasBlankLineBetween(int blockEndLine, int statementLine) {
    return IntStream.range(blockEndLine, statementLine - 1)
        .mapToObj(this::getLine)
        .anyMatch(String::isBlank);
  }

  // Imaginary nodes such as EXPR sit on the line of their first child, which for a call chain is
  // the outermost call's opening parenthesis, so the earliest line among all descendants is the
  // line the statement actually starts on.
  private static int firstLineOf(DetailAST node) {
    var firstLine = node.getLineNo();
    for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      firstLine = Math.min(firstLine, firstLineOf(child));
    }

    return firstLine;
  }

  private static DetailAST lastDescendantOf(DetailAST node) {
    var lastDescendant = node;
    while (lastDescendant.hasChildren()) {
      lastDescendant = lastDescendant.getLastChild();
    }

    return lastDescendant;
  }
}
