package com.zjlp.face.titan.impl;

import com.thinkaurelius.titan.core.SchemaViolationException;
import com.thinkaurelius.titan.core.TitanGraph;
import com.zjlp.face.bean.UserVertexIdPair;
import com.zjlp.face.titan.ITitanDAO;
import com.zjlp.face.titan.TitanConPool;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.T;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

@Service("TitanDAOImpl")
public class TitanDAOImpl extends TitanConPool implements ITitanDAO, Serializable {
    private static EsDAOImpl esDAO = new EsDAOImpl();

    private static final Logger LOGGER = LoggerFactory.getLogger(TitanDAOImpl.class);


    /**
     * 新增一个用户
     *
     * @param userId
     * @return
     */
    public String addUser(String userId, TitanGraph graph) {
        String vid = null;
        try {
            vid = graph.addVertex(T.label, "person", "userId", userId).id().toString();
            esDAO.create(new UserVertexIdPair(userId, vid));
            graph.tx().commit();
            LOGGER.debug("添加新用户:" + userId);
        } catch (IOException e) {
            LOGGER.error("往ES中插入doc失败: userId '" + userId + "'", e);
        } catch (Exception e) {
            LOGGER.error("用户'" + userId + "'已经存在,插入失败", e);
            graph.tx().rollback();
        }
        return vid;
    }

    /**
     * 通过顶点id，增加一个好友关系，给Spark批量处理用
     *
     * @param userVId
     * @param friendVId
     */
    public void addRelationByVID(String userVId, String friendVId, TitanGraph graph) throws Exception {
        GraphTraversalSource g = graph.traversal();
        try {
            g.V(userVId).next().addEdge("knows", g.V(friendVId).next());
            g.tx().commit();
            LOGGER.debug("添加好友关系:" + userVId + " -knows-> " + friendVId);
        } catch (SchemaViolationException e) {
            g.tx().rollback();
            LOGGER.warn("已经存在这条边:" + userVId + " -knows-> " + friendVId, e);
        } catch (Exception e) {
            g.tx().rollback();
            LOGGER.error("addRelationByVID出现异常", e);
            throw e;
        }
    }

    /**
     * 增加一个好友关系
     *
     * @param userId
     * @param friendUserId
     */
    public void addRelation(String userId, String friendUserId) {
        String userVId = esDAO.getVertexId(userId);
        String friendVId = esDAO.getVertexId(friendUserId);
        TitanGraph graph = getTitanGraph(userId);
        if (userVId == null) {
            userVId = addUser(userId, graph);
        }
        if (friendVId == null) {
            friendVId = addUser(friendUserId, graph);
        }
        try {
            addRelationByVID(userVId, friendVId, graph);
        } catch (Exception e) {

            //主要为了防止 Titan的顶点已经删除，而ES的doc未删除的特殊情况
            if (!graph.traversal().V().has("userId", userId).hasNext()) {
                userVId = addUser(userId, graph);
            }
            if (!graph.traversal().V().has("userId", friendUserId).hasNext()) {
                friendVId = addUser(userId, graph);
            }
            try {
                addRelationByVID(userVId, friendVId, graph);
            } catch (Exception e2) {

            }
        }
    }

    /**
     * 删除一个好友关系
     *
     * @param userId
     * @param friendUserId
     */
    public void deleteRelation(String userId, String friendUserId) {
        try {
            GraphTraversalSource g = getTitanGraph(userId).traversal();
            g.V(esDAO.getVertexId(userId)).outE() //.hasLabel("knows")
                    .where(__.otherV().values("userId").is(friendUserId)).drop().iterate();
            g.tx().commit();
        } catch (FastNoSuchElementException e) {
            LOGGER.warn("deleteRelation 失败,userId:" + userId + ",找不到相应的 vertex id。", e);
        } catch (Exception e) {

        }
    }

    public Set getAllFriendVIds(String userVId) {
        GraphTraversal oneDegreeFriends = getTitanGraph().traversal().V(userVId).out("knows").id();
        return oneDegreeFriends.toSet();
    }

    private List<String> getOneDegreeFriends(String userVId, String[] friendsVId, TitanGraph graph) {
        GraphTraversal oneDegreeFriends = graph.traversal().V(userVId).out("knows").
                where(__.hasId(friendsVId)).values("userId");
        return oneDegreeFriends.toList();
    }


    private List<String> getTwoDegreeFriends(String userVId, String[] friendsVId, TitanGraph graph) {
        GraphTraversal twoDegreeFriends = graph.traversal().V(userVId).aggregate("u").out("knows").
                aggregate("f1").out("knows").
                where(__.hasId(friendsVId)).
                where(P.without("f1")).
                where(P.without("u")).
                dedup().values("userId");
        return twoDegreeFriends.toList();
    }

    /**
     * 获取二度好友
     *
     * @param userId
     * @param friends
     * @return
     */
    public Map<String, Integer> getFriendsLevel(String userId, List<String> friends) {
        Map<String, Integer> result = new HashMap<String, Integer>();
        String userVID = esDAO.getVertexId(userId);
        String[] friendsVID = esDAO.getVertexIds(friends);
        TitanGraph graph = getTitanGraph(userId);
        if (userVID != null && friendsVID.length > 0) {
            List<String> oneDegreeFriends = getOneDegreeFriends(userVID, friendsVID, graph);
            List<String> twoDegreeFriends = getTwoDegreeFriends(userVID, friendsVID, graph);
            for (String friend : twoDegreeFriends) {
                result.put(friend, 2);
            }
            for (String friend : oneDegreeFriends) {
                result.put(friend, 1);
            }
        }
        return result;
    }

    /**
     * 获取共同好友数
     *
     * @param userId
     * @param friends
     * @return
     */
    public Map<Object, Long> getComFriendsNum(String userId, List<String> friends) {
        String userVID = esDAO.getVertexId(userId);
        String[] vids = esDAO.getVertexIds(friends);
        if (userVID == null || vids.length == 0) {
            LOGGER.info("不存在userId:" + userId + "顶点");
            return new HashMap<Object, Long>();
        } else {
            return getTitanGraph(userId).traversal().V(userVID)
                    .aggregate("u")
                    .out("knows").out("knows")
                    .where(__.hasId(vids))
                    .where(P.without("u"))
                    .values("userId").groupCount().next();
        }
    }

    public static void main(String[] args) {


    }

/*    private class CacheHotUserThread extends Thread {
        private String userId;

        public CacheHotUserThread(String name) {
            super(name);//调用父类带参数的构造方法
        }

        public CacheHotUserThread(String name, String userId) {
            new CacheHotUserThread(name);
            this.userId = userId;
        }

        public void run() {
            LOGGER.info("热点用户(" + this.userId + ")开始缓存");
            List<String> friends = new ArrayList<String>();
            friends.add(this.userId);
            getComFriendsNum(this.userId, friends);
            LOGGER.info("热点用户(" + this.userId + ")缓存完毕");
        }
    }


    public void cacheHotUsers() {
        List<String> hotUsers = esDAO.getHotUsers();
        long interval = Integer.valueOf(Props.get("cache-interval-hot-user"));
        long begin = System.currentTimeMillis();
        LOGGER.info("开始缓存热点用户");
        for (String hotUser : hotUsers) {
            //如果该方法执行了50分钟还没执行完，那么就不再执行了
            if ((System.currentTimeMillis() - begin) > 3000000) return;
            new CacheHotUserThread("thread_" + hotUser, hotUser).run();
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }*/
}
