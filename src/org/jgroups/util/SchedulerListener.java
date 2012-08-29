// $Id: SchedulerListener.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.util;


public interface SchedulerListener {
    void started(Runnable   r);
    void stopped(Runnable   r);
    void suspended(Runnable r);
    void resumed(Runnable   r);
}
