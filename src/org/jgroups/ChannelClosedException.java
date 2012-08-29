// $Id: ChannelClosedException.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;


public class ChannelClosedException extends ChannelException {

    public ChannelClosedException() {
        super();
    }

    public ChannelClosedException(String msg) {
        super(msg);
    }

    public String toString() {
        return "ChannelClosedException";
    }
}
