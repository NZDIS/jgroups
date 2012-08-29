// $Id: ChannelNotConnectedException.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups;


public class ChannelNotConnectedException extends ChannelException {

    public ChannelNotConnectedException() {
    }

    public ChannelNotConnectedException(String reason) {
        super(reason);
    }

    public String toString() {
        return "ChannelNotConnectedException";
    }
}
