// $Id: RequestHandler.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $

package org.jgroups.blocks;


import org.jgroups.Message;


public interface RequestHandler {
    Object handle(Message msg);
}
