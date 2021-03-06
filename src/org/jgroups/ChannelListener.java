// $Id: ChannelListener.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;


/**
 * Allows a listener to be notified when important channel events occur. For example, when
 * a channel is closed, a PullPushAdapter can be notified, and stop accordingly.
 */
public interface ChannelListener {
    void channelConnected(Channel channel);
    void channelDisconnected(Channel channel);
    void channelClosed(Channel channel);
    void channelShunned();
    void channelReconnected(Address addr);
}
