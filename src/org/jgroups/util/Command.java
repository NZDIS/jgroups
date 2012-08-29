// $Id: Command.java,v 1.1 2008/09/03 04:24:41 commerce\wuti7102 Exp $

package org.jgroups.util;

/**
  * The Command patttern (se Gamma et al.). Implementations would provide their
  * own <code>execute</code> method.
  * @author Bela Ban
  */
public interface Command {
    boolean execute();
}
