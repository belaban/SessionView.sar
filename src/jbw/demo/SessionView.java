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
import java.io.Serializable;
import java.util.*;


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


    /** Lists all sessions (local and non-local */
    public String listSessions2() {
        try {
            List<String> rsps=partition.callMethodOnCluster(SERVICE_NAME, "_listAllSessions", null, null,
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

   
    /** Lists only sessions which are stored on this node as primary or backup (Infinispan's DIST mode) */
    public String listSessions() {
        try {
            List<Data> rsps=partition.callMethodOnCluster(SERVICE_NAME, "_listSessions", null, null,
                                                          Data.class, false, null, 10000, false);

            // map of contexts and a map<session-id,Set<host>> which store it
            Map<String,Map<String,Set<String>>> map=new HashMap<String,Map<String,Set<String>>>();

            for(Data data: rsps) {
                String host=data.host;
                if(data.sessions != null) {
                    for(Map.Entry<String,Set<String>> entry: data.sessions.entrySet()) {
                        String context=entry.getKey();
                        Set<String> session_ids=entry.getValue();
                        add(map, host, context, session_ids);
                    }
                }
            }

            StringBuilder sb=new StringBuilder();
            for(Map.Entry<String,Map<String,Set<String>>> entry: map.entrySet()) {
                String context=entry.getKey();
                Map<String,Set<String>> sessions=entry.getValue();
                sb.append(context).append(":\n");
                for(Map.Entry<String,Set<String>> entry2: sessions.entrySet())
                    sb.append("  ").append(entry2.getKey()).append(": ").append(entry2.getValue()).append("\n");
                sb.append("\n");
            }
            return sb.toString();
        }
        catch(InterruptedException e) {
            return e.toString();
        }
    }

    protected static void add(Map<String,Map<String,Set<String>>> map, String host, String context, Set<String> session_ids) {
        Map<String,Set<String>> sessions=map.get(context);
        if(sessions == null) {
            sessions=new HashMap<String,Set<String>>();
            map.put(context, sessions);
        }
        for(String session_id: session_ids) {
            Set<String> hosts=sessions.get(session_id);
            if(hosts == null) {
                hosts=new HashSet<String>();
                sessions.put(session_id, hosts);
            }
            hosts.add(host);
        }
    }



     public Data _listSessions() {
         MBeanServer mbean_server=getMBeanServer();
         ObjectName query=null;
         Data data=null;
         try {
             data=new Data(getLocalAddress());
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
                                 String ids=mgr.listSessionIds();
                                 if(ids != null && ids.trim().length() > 0) {
                                     List<String> sessions=parseSessionIds(ids);
                                     if(!sessions.isEmpty()) {
                                         for(String session: sessions)
                                             data.add(context.getName(), session);
                                     }
                                 }
                             }
                         }
                     }
                 }
             }
             return data;
         }
         catch(Exception e) {
             e.printStackTrace();
             return null;
         }
     }



    public String _listAllSessions() {
        MBeanServer mbean_server=getMBeanServer();
        ObjectName query=null;
        StringBuilder sb=new StringBuilder(getLocalAddress() + ":\n");
         try {
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
                                 String ids=mgr.listSessionIds();
                                 if(ids != null && ids.trim().length() > 0) {
                                     List<String> sessions=parseSessionIds(ids);
                                     if(!sessions.isEmpty())
                                         for(String session: sessions)
                                             sb.append("  ").append(context.getName()).append(": ").append(session + "\n");
                                 }
                             }
                         }
                     }
                 }
             }
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


    public static class Data implements Serializable {
        private static final long serialVersionUID=7765144547528381044L;
        protected String host;

        // map of contexts and their associated session-ids
        protected Map<String,Set<String>> sessions;

        public Data() {
        }

        public Data(String host) {
            this.host=host;
        }

        public void add(String context, String session_id) {
            if(context == null || session_id == null)
                return;
            if(sessions == null)
                sessions=new HashMap<String,Set<String>>();
            Set<String> session_ids=sessions.get(context);
            if(session_ids == null) {
                session_ids=new HashSet<String>();
                sessions.put(context, session_ids);
            }
            session_ids.add(session_id);
        }

        public String toString() {
            StringBuilder sb=new StringBuilder(host + ":\n");
            if(sessions != null)
                for(Map.Entry<String,Set<String>> entry: sessions.entrySet())
                    sb.append("   ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            return sb.toString();
        }
    }



}
