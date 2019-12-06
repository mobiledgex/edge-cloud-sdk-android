package com.mobiledgex.matchingengine;

public class InvalidPortException extends Exception {

  public InvalidPortException(String msg) {
    super(msg);
  }

  public InvalidPortException(String msg, Exception innerException) {
    super(msg, innerException);
  }
}
