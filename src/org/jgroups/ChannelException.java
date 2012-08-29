// $Id: ChannelException.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups;


public class ChannelException extends Exception {

    public ChannelException() {
        super();
    }

    public ChannelException(String reason) {
        super(reason);
    }

    public String toString() {
        return "ChannelException: " + getMessage();
    }
}
