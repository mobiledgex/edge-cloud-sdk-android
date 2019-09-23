package com.mobiledgex.matchingengine;

public class DmeDnsException extends Exception {
    public DmeDnsException(String msg) {
        super(msg);
    }

    public DmeDnsException(String msg, Exception innerException) {
        super(msg, innerException);
    }
}
