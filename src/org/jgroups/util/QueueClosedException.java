// $Id: QueueClosedException.java,v 1.1 2008/09/03 04:24:41 commerce\wuti7102 Exp $

package org.jgroups.util;


public class QueueClosedException extends Exception {

    public QueueClosedException() {

    }

    public QueueClosedException( String msg )
    {
        super( msg );
    }

    public String toString() {
        if ( this.getMessage() != null )
            return "QueueClosedException:" + this.getMessage();
        else
            return "QueueClosedException";
    }
}
