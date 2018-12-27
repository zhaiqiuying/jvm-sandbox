package java.com.alibaba.jvm.sandbox.spy;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 间谍类，藏匿在各个ClassLoader中
 * <p>
 * 从{@code 0.0.0.v}版本之后,因为要考虑能在alipay的CloudEngine环境中使用,这个环境只能向上查找java.开头的包路径.
 * 所以这里只好把Spy的包路径前缀中增加了java.开头
 * </p>
 * <p>
 * <p>
 * 从{@code 1.1.0}版本之后，修复了命名空间在Spy中不支持的问题
 * </p>
 *
 * @author luanjia@taobao.com
 */
public class Spy {

    private static final Class<Spy.Ret> SPY_RET_CLASS = Spy.Ret.class;

    private static final Map<String, MethodHook> namespaceMethodHookMap
            = new ConcurrentHashMap<String, MethodHook>();

    /**
     * 判断间谍类是否已经完成初始化
     *
     * @param namespace 命名空间
     * @return TRUE:已完成初始化;FALSE:未完成初始化;
     */
    public static boolean isInit(final String namespace) {
        return namespaceMethodHookMap.containsKey(namespace);
    }

    /**
     * 初始化间谍
     *
     * @param namespace             命名空间
     * @param ON_BEFORE_METHOD      ON_BEFORE 回调
     * @param ON_RETURN_METHOD      ON_RETURN 回调
     * @param ON_THROWS_METHOD      ON_THROWS 回调
     * @param ON_LINE_METHOD        ON_LINE 回调
     * @param ON_CALL_BEFORE_METHOD ON_CALL_BEFORE 回调
     * @param ON_CALL_RETURN_METHOD ON_CALL_RETURN 回调
     * @param ON_CALL_THROWS_METHOD ON_CALL_THROWS 回调
     */
    public static void init(final String namespace,
                            final Method ON_BEFORE_METHOD,
                            final Method ON_RETURN_METHOD,
                            final Method ON_THROWS_METHOD,
                            final Method ON_LINE_METHOD,
                            final Method ON_CALL_BEFORE_METHOD,
                            final Method ON_CALL_RETURN_METHOD,
                            final Method ON_CALL_THROWS_METHOD) {
        namespaceMethodHookMap.put(
                namespace,
                new MethodHook(
                        ON_BEFORE_METHOD,
                        ON_RETURN_METHOD,
                        ON_THROWS_METHOD,
                        ON_LINE_METHOD,
                        ON_CALL_BEFORE_METHOD,
                        ON_CALL_RETURN_METHOD,
                        ON_CALL_THROWS_METHOD
                )
        );
    }

    /**
     * 清理间谍钩子方法
     *
     * @param namespace 命名空间
     */
    public static void clean(final String namespace) {
        namespaceMethodHookMap.remove(namespace);

        // 如果是最后的一个命名空间，则需要重新清理Node中所持有的Thread
        if (namespaceMethodHookMap.isEmpty()) {
            for (int index = 0; index < selfCallBarrier.nodeArray.length; index++) {
                selfCallBarrier.nodeArray[index] = new SelfCallBarrier.Node();
            }
        }

    }

    private static final SelfCallBarrier selfCallBarrier = new SelfCallBarrier();

    public static void spyMethodOnCallBefore(final int lineNumber,
                                             final String owner,
                                             final String name,
                                             final String desc,
                                             final String namespace,
                                             final int listenerId) throws Throwable {
        namespaceMethodHookMap.get(namespace).ON_CALL_BEFORE_METHOD.invoke(null, listenerId, lineNumber, owner, name, desc);
    }

    public static void spyMethodOnCallReturn(final String namespace,
                                             final int listenerId) throws Throwable {
        namespaceMethodHookMap.get(namespace).ON_CALL_RETURN_METHOD.invoke(null, listenerId);
    }

    public static void spyMethodOnCallThrows(final String throwException,
                                             final String namespace,
                                             final int listenerId) throws Throwable {
        namespaceMethodHookMap.get(namespace).ON_CALL_THROWS_METHOD.invoke(null, listenerId, throwException);
    }

    public static void spyMethodOnLine(final int lineNumber,
                                       final String namespace,
                                       final int listenerId) throws Throwable {
        namespaceMethodHookMap.get(namespace).ON_LINE_METHOD.invoke(null, listenerId, lineNumber);
    }

    public static Ret spyMethodOnBefore(final Object[] argumentArray,
                                        final String namespace,
                                        final int listenerId,
                                        final int targetClassLoaderObjectID,
                                        final String javaClassName,
                                        final String javaMethodName,
                                        final String javaMethodDesc,
                                        final Object target) throws Throwable {
        final Thread thread = Thread.currentThread();
        if (selfCallBarrier.isEnter(thread)) {
            return Ret.RET_NONE;
        }
        final SelfCallBarrier.Node node = selfCallBarrier.enter(thread);
        try {
            return (Ret) namespaceMethodHookMap.get(namespace).ON_BEFORE_METHOD.invoke(null,
                    listenerId, targetClassLoaderObjectID, SPY_RET_CLASS, javaClassName, javaMethodName, javaMethodDesc, target, argumentArray);
        } finally {
            selfCallBarrier.exit(thread, node);
        }
    }

    public static Ret spyMethodOnReturn(final Object object,
                                        final String namespace,
                                        final int listenerId) throws Throwable {
        final Thread thread = Thread.currentThread();
        if (selfCallBarrier.isEnter(thread)) {
            return Ret.RET_NONE;
        }
        final SelfCallBarrier.Node node = selfCallBarrier.enter(thread);
        try {
            return (Ret) namespaceMethodHookMap.get(namespace).ON_RETURN_METHOD.invoke(null, listenerId, SPY_RET_CLASS, object);
        } finally {
            selfCallBarrier.exit(thread, node);
        }
    }

    public static Ret spyMethodOnThrows(final Throwable throwable,
                                        final String namespace,
                                        final int listenerId) throws Throwable {
        final Thread thread = Thread.currentThread();
        if (selfCallBarrier.isEnter(thread)) {
            return Ret.RET_NONE;
        }
        final SelfCallBarrier.Node node = selfCallBarrier.enter(thread);
        try {
            return (Ret) namespaceMethodHookMap.get(namespace).ON_THROWS_METHOD.invoke(null, listenerId, SPY_RET_CLASS, throwable);
        } finally {
            selfCallBarrier.exit(thread, node);
        }
    }

    /**
     * 返回结果
     */
    public static class Ret {

        public static final int RET_STATE_NONE = 0;
        public static final int RET_STATE_RETURN = 1;
        public static final int RET_STATE_THROWS = 2;
        private static final Ret RET_NONE = new Ret(RET_STATE_NONE, null);
        /**
         * 返回状态(0:NONE;1:RETURN;2:THROWS)
         */
        public final int state;
        /**
         * 应答对象
         */
        public final Object respond;

        /**
         * 构造返回结果
         *
         * @param state   返回状态
         * @param respond 应答对象
         */
        private Ret(int state, Object respond) {
            this.state = state;
            this.respond = respond;
        }

        public static Ret newInstanceForNone() {
            return RET_NONE;
        }

        public static Ret newInstanceForReturn(Object object) {
            return new Ret(RET_STATE_RETURN, object);
        }

        public static Ret newInstanceForThrows(Throwable throwable) {
            return new Ret(RET_STATE_THROWS, throwable);
        }

    }

    /**
     * 本地线程
     */
    public static class SelfCallBarrier {

        public static class Node {
            private final Thread thread;
            private Node pre;
            private Node next;

            Node() {
                this(null);
            }

            Node(final Thread thread) {
                this.thread = thread;
            }

        }

        // 删除节点
        void delete(final Node node) {
            node.pre.next = node.next;
            if (null != node.next) {
                node.next.pre = node.pre;
            }
            // help gc
            node.pre = (node.next = null);
        }

        // 插入节点
        void insert(final Node top, final Node node) {
            if (null != top.next) {
                top.next.pre = node;
            }
            node.next = top.next;
            node.pre = top;
            top.next = node;
        }

        static final int THREAD_LOCAL_ARRAY_LENGTH = 1024;

        final Node[] nodeArray = new Node[THREAD_LOCAL_ARRAY_LENGTH];

        SelfCallBarrier() {
            // init root node
            for (int i = 0; i < THREAD_LOCAL_ARRAY_LENGTH; i++) {
                nodeArray[i] = new Node();
            }
        }

        int abs(int val) {
            return val < 0
                    ? val * -1
                    : val;
        }

        boolean isEnter(Thread thread) {
            final Node top = nodeArray[abs(thread.hashCode()) % THREAD_LOCAL_ARRAY_LENGTH];
            Node node = top;
            synchronized (top) {
                while (null != node.next) {
                    node = node.next;
                    if (thread == node.thread) {
                        return true;
                    }
                }
                return false;
            }//sync
        }

        Node enter(Thread thread) {
            final Node top = nodeArray[abs(thread.hashCode()) % THREAD_LOCAL_ARRAY_LENGTH];
            final Node node = new Node(thread);
            synchronized (top) {
                insert(top, node);
            }
            return node;
        }

        void exit(Thread thread, Node node) {
            final Node top = nodeArray[abs(thread.hashCode()) % THREAD_LOCAL_ARRAY_LENGTH];
            synchronized (top) {
                delete(node);
            }
        }

    }


    /**
     * 回调方法钩子
     */
    public static class MethodHook {

        private final Method ON_BEFORE_METHOD;
        private final Method ON_RETURN_METHOD;
        private final Method ON_THROWS_METHOD;
        private final Method ON_LINE_METHOD;
        private final Method ON_CALL_BEFORE_METHOD;
        private final Method ON_CALL_RETURN_METHOD;
        private final Method ON_CALL_THROWS_METHOD;

        public MethodHook(final Method on_before_method,
                          final Method on_return_method,
                          final Method on_throws_method,
                          final Method on_line_method,
                          final Method on_call_before_method,
                          final Method on_call_return_method,
                          final Method on_call_throws_method) {
            this.ON_BEFORE_METHOD = on_before_method;
            this.ON_RETURN_METHOD = on_return_method;
            this.ON_THROWS_METHOD = on_throws_method;
            this.ON_LINE_METHOD = on_line_method;
            this.ON_CALL_BEFORE_METHOD = on_call_before_method;
            this.ON_CALL_RETURN_METHOD = on_call_return_method;
            this.ON_CALL_THROWS_METHOD = on_call_throws_method;
        }
    }

}
