// $Id: SuspectedException.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;


public class SuspectedException extends Exception {
    Object suspect=null;

    public SuspectedException()                {}
    public SuspectedException(Object suspect)  {this.suspect=suspect;}

    public String toString() {return "SuspectedException";}
}
