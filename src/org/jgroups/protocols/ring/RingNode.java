//$Id: RingNode.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups.protocols.ring;

import java.util.Vector;
import org.jgroups.stack.IpAddress;

public interface RingNode
{
   Object receiveToken(int timeout) throws TokenLostException;

   Object receiveToken() throws TokenLostException;

   void passToken(Object token) throws TokenLostException;

   IpAddress getTokenReceiverAddress();

   void reconfigure(Vector newMembers);

   void tokenArrived(Object token);
}
