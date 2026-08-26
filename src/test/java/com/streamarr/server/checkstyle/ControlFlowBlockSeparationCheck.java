package com.streamarr.server.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;

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

    var lastToken = lastDescendantOf(controlFlow);
    if (followingStatement.getLineNo() > lastToken.getLineNo() + 1) {
      return;
    }

    log(followingStatement, MSG_SEPARATION);
  }

  private static DetailAST lastDescendantOf(DetailAST node) {
    var lastDescendant = node;
    while (lastDescendant.hasChildren()) {
      lastDescendant = lastDescendant.getLastChild();
    }

    return lastDescendant;
  }
}
