// $Id: MessageListener.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups;



public interface MessageListener {
    void          receive(Message msg);
    byte[]        getState();
    void          setState(byte[] state);
}
