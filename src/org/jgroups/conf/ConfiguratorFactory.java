// $Id: ConfiguratorFactory.java,v 1.1 2008/09/03 04:24:45 commerce\wuti7102 Exp $

package org.jgroups.conf;

import java.net.URL;
import java.io.IOException;

/**
 * The ConfigurationFactory is a factory that returns a protocol stack configurator.
 * The protocol stack configurator is an object that read a stack configuration and
 * parses it so that the ProtocolStack can create a stack.
 * <BR>
 * Currently the factory returns one of the following objects:<BR>
 * 1. XmlConfigurator - parses XML files that are according to the jgroups-protocol.dtd<BR>
 * 2. PlainConfigurator - uses the old style strings UDP:FRAG: etc etc<BR>
 * @author Filip Hanik (<a href="mailto:filip@filip.net">filip@filip.net)
 * @version 1.0
 */
public class ConfiguratorFactory
{
    public static final String JAR_MISSING_ERROR =
    "JAXP Error: XML Parsing libraries are not in your classpath. Make sure you have JAXP compatible "+
    "libraries in your classpath. JGroups include the Apache Xerces 2.0 parser, the two libraries: "+
    "xercesxmlapi and xercesimpl can be found in the <JG_ROOT>/lib directory.";

    protected ConfiguratorFactory()
    {
    }

    /**
     * Returns a protocol stack configurator based on the properties passed in.<BR>
     * If the properties parameter is a plain string UDP:FRAG:MERGE:GMS etc, a PlainConfigurator is returned.<BR>
     * If the properties parameter is a string that represents a url for example http://www.filip.net/test.xml
     * or the parameter is a java.net.URL object, an XmlConfigurator is returned<BR>
     * @param properties old style property string, url string, or java.net.URL object
     * @return a ProtocolStackConfigurator containing the stack configuration
     * @exception IOException if it fails to parse the XML content
     * @exception IOException if the URL is invalid or a the content can not be reached
     */
    public static ProtocolStackConfigurator getStackConfigurator(Object properties) throws IOException {

        // Is it a URL ?
        if(properties instanceof URL) {
          System.out.println("JGroups_FRAGme does not support creating Protocal stacks from a given URL.");
          System.out.println("Define Protocol int the old style (UDP:FRAG:MERGE etc.)");
          System.exit(1);
        }

        return new PlainConfigurator((String)properties);
    }
}
