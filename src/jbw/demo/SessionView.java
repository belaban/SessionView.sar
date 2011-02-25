package jbw.demo;

import org.apache.catalina.*;
import org.jboss.beans.metadata.api.annotations.*;
import org.jboss.ha.framework.interfaces.ClusterNode;
import org.jboss.ha.framework.interfaces.GroupMembershipListener;
import org.jboss.ha.framework.interfaces.HAPartition;
import org.jboss.web.tomcat.service.session.JBossCacheManager;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;


/**
 * @author Bela Ban
 * @version $Id$
 */
public class SessionView implements GroupMembershipListener, SessionViewMBean {
    private HAPartition partition;
    private static final String SERVICE_NAME="SessionDisplay";


    public SessionView() {
    }


    public HAPartition getPartition() {
        return partition;
    }

    @Inject(bean="HAPartition")
    public void setPartition(HAPartition partition) {
        this.partition=partition;
    }

    
    public String getLocalAddress() {
        ClusterNode me=partition != null? partition.getClusterNode() : null;
        return me != null? me.toString() : "n/a";
    }

    @Create
    public void create() throws Exception {
        if(partition == null)
            throw new NullPointerException("partition is null");

        partition.registerGroupMembershipListener(this);
        partition.registerRPCHandler(SERVICE_NAME, this);
        System.out.println(getClass().getSimpleName() + " bean created");
    }


    @Start
    public void start() {
        System.out.println(getClass().getSimpleName() + " bean started");
    }

    @Stop
    public void stop() {
        System.out.println(getClass().getSimpleName() + " bean stopped");
    }

    @Destroy
    public void destroy() {
        partition.unregisterRPCHandler(SERVICE_NAME, this);
        System.out.println(getClass().getSimpleName() + " bean destroyed");
    }


    /** Callback, fetches all the sessionids from all cluster nodes */
    public String dumpSessionIds() {
        try {
            List<String> rsps=partition.callMethodOnCluster(SERVICE_NAME, "_getSessionIds", null, null,
                                                            String.class, false, null, 10000, false);
            StringBuilder sb=new StringBuilder();
            for(String rsp: rsps)
                sb.append(rsp).append("\n");
            return sb.toString();
        }
        catch(InterruptedException e) {
            return e.toString();
        }
    }

    /** Callback, fetches all the sessionids from all cluster nodes */
    public String dumpSessionIds2() {
        try {
            List<String> rsps=partition.callMethodOnCluster(SERVICE_NAME, "_getSessionIds2", null, null,
                                                            String.class, false, null, 10000, false);
            StringBuilder sb=new StringBuilder();
            for(String rsp: rsps)
                sb.append(rsp).append("\n");
            return sb.toString();
        }
        catch(InterruptedException e) {
            return e.toString();
        }
    }

    /** Returns all the web sessions on this node */
    public String _getSessionIds() {
        MBeanServer mbean_server=getMBeanServer();
        ObjectName query=null;
        try {
            query=new ObjectName("jboss.web:*,type=Manager");
            StringBuilder sb=new StringBuilder(getLocalAddress() + ":\n");
            Set<ObjectName> names=mbean_server.queryNames(query, null);
            for(ObjectName name: names) {
                Object sessions=mbean_server.invoke(name, "listSessionIds", null, null);
                if(sessions != null && (sessions instanceof String && ((String)sessions).trim().length() > 0)) {
                    String context=name.getKeyProperty("path");
                    if(context == null)
                        context=name.toString();
                    sb.append(context + ": " + sessions).append("\n");
                }
            }
            sb.append("\n");
            return sb.toString();
        }
        catch(Exception e) {
            return e.toString();
        }
    }

     public String _getSessionIds2() {
         MBeanServer mbean_server=getMBeanServer();
         ObjectName query=null;
         try {
             StringBuilder sb=new StringBuilder(getLocalAddress() + ":\n");
             query=new ObjectName("jboss.web:type=Server");
             Set<ObjectName> names=mbean_server.queryNames(query, null);
             for(ObjectName name: names) {
                 Service[] services=(Service[])mbean_server.invoke(name, "findServices", null, null);
                 for(Service service: services) {
                     Engine engine=(Engine)service.getContainer();
                     for(Container host: engine.findChildren()) {
                         for(Container child: host.findChildren()) {
                             Context context=(Context)child;
                             Manager manager=context.getManager();
                             if(manager instanceof JBossCacheManager) {
                                 JBossCacheManager mgr=(JBossCacheManager)manager;
                                 String route=mgr.getJvmRoute();
                                 String ids=mgr.listSessionIds();
                                 if(ids != null && ids.trim().length() > 0) {
                                     List<String> sessions=parseSessionIds(ids);
                                     if(!sessions.isEmpty()) {
                                         sb.append(context.getName() + " (route=" + route + "):\n");
                                         for(String session: sessions) {
                                             sb.append(session);
                                             String location=mgr.locate(session);
                                             boolean is_local=mgr.isLocal(session);
                                             sb.append(" (location=" + location + ", is_local=" + is_local + "\n");
                                         }
                                     }
                                 }
                             }
                         }
                     }
                 }
             }
             sb.append("\n");
             return sb.toString();
         }
         catch(Exception e) {
             return e.toString();
         }
     }


    public void membershipChanged(List<ClusterNode> clusterNodes, List<ClusterNode> clusterNodes1, List<ClusterNode> clusterNodes2) {
        if(partition != null)
            System.out.println("view change: " + partition.getCurrentView());
    }

    public void membershipChangedDuringMerge(List<ClusterNode> clusterNodes, List<ClusterNode> clusterNodes1, List<ClusterNode> clusterNodes2, List<List<ClusterNode>> lists) {
    }


    protected static MBeanServer getMBeanServer() {
        ArrayList servers = MBeanServerFactory.findMBeanServer(null);
        if (servers != null && !servers.isEmpty()) {
            // return 'jboss' server if available
            for (int i = 0; i < servers.size(); i++) {
                MBeanServer srv = (MBeanServer) servers.get(i);
                if ("jboss".equalsIgnoreCase(srv.getDefaultDomain()))
                    return srv;
            }

            // return first available server
            return (MBeanServer) servers.get(0);
        }
        else {
            //if it all fails, create a default
            return MBeanServerFactory.createMBeanServer();
		}
    }


    protected static List<String> parseSessionIds(String input) {
        List<String> sessions=new ArrayList<String>();
        StringTokenizer tokenizer=new StringTokenizer(input, ",");
        while(tokenizer.hasMoreTokens())
            sessions.add(tokenizer.nextToken());
        return sessions;
    }


   



}
