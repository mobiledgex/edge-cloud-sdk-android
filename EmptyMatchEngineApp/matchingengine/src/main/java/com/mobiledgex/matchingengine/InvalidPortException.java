package com.mobiledgex.matchingengine;

/*!
 * Occurs when port specified by application is not found in port range returned by FindCloudletReply
 * \ingroup exceptions_getconnection
 */
public class InvalidPortException extends Exception {

  public InvalidPortException(String msg) {
    super(msg);
  }

  public InvalidPortException(String msg, Exception innerException) {
    super(msg, innerException);
  }
}
