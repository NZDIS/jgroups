// $Id: JChannelFactory.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups;

public class JChannelFactory implements ChannelFactory {
    public Channel createChannel(Object properties) throws ChannelException {
	return new JChannel(properties);
    }
}
