// $Id: ProtocolStackConfigurator.java,v 1.1 2008/09/03 04:24:45 commerce\wuti7102 Exp $

package org.jgroups.conf;

/**
 * @author Filip Hanik (<a href="mailto:filip@filip.net">filip@filip.net)
 * @version 1.0
 */

public interface ProtocolStackConfigurator
{
    String         getProtocolStackString();
    ProtocolData[] getProtocolStack();
}
