/*
 * Copyright (C) 2012-2020 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Java and Android.
 * Events are posted ({@link #post(Object)}) to the bus, which delivers it to subscribers that have a matching handler
 * method for the event type.
 * To receive events, subscribers must register themselves to the bus using {@link #register(Object)}.
 * Once registered, subscribers receive events until {@link #unregister(Object)} is called.
 * Event handling methods must be annotated by {@link Subscribe}, must be public, return nothing (void),
 * and have exactly one parameter (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    //region 参数
    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";
    //单例模式
    static volatile EventBus defaultInstance;
    //默认的defalut builder
    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();

//    查找 eventClass 的所有父类和接口，包括他自己
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    //event 和 Subscription 的map
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;

    //一个Subscriber实例有多个event
    private final Map<Object, List<Class<?>>> typesBySubscriber;

    //stick event的class 和 stickevent实例，只保存最新的实例
    private final Map<Class<?>, Object> stickyEvents;

    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    // @Nullable
    private final Poster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    private final int indexCount;
    private final Logger logger;
    //endregion

    //region 构造函数
    /** Convenience singleton for apps using a process-wide EventBus instance. */
    //单例模式
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        logger = builder.getLogger();

        subscriptionsByEventType = new HashMap<>();

        typesBySubscriber = new HashMap<>();

        stickyEvents = new ConcurrentHashMap<>();

        mainThreadSupport = builder.getMainThreadSupport();
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        //false false
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);

        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }
    //endregion

    //region register
    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     */
    public void register(Object subscriber) {
        // 首先获得class对象
        Class<?> subscriberClass = subscriber.getClass();
        // 通过 subscriberMethodFinder 来找到订阅者订阅了哪些事件.返回一个 SubscriberMethod 对象的 List, SubscriberMethod
        // 里包含了这个方法的 Method 对象,以及将来响应订阅是在哪个线程的 ThreadMode ,以及订阅的事件类型 eventType ,以及订阅的优
        // 先级 priority ,以及是否接收粘性 sticky 事件的 boolean 值，其实就是解析这个类上的所有 Subscriber 注解方法属性。
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                // 订阅
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    // Must be called in synchronized block
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        // 获取方法参数的 class
        Class<?> eventType = subscriberMethod.eventType;
        // 创建一个 Subscription
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        // 获取订阅了此事件类的所有订阅者信息列表
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions == null) {
            // 线程安全的 ArrayList
            subscriptions = new CopyOnWriteArrayList<>();
            // 添加
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            // 是否包含，如果包含再次添加抛异常
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }
        // 处理优先级
        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }
        // 通过 subscriber 获取  List<Class<?>>
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        // 将此事件类加入 订阅者事件类列表中
        subscribedEvents.add(eventType);
        // 处理粘性事件，如果粘性事件，将最近的粘性事件实例，发送给方法，触发方法
        //todo
        if (subscriberMethod.sticky) {
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
//                    isAssignableFrom()方法是判断是否为某个类的父类，instanceof关键字是判断是否某个类的子类。
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    //todo
    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, stickyEvent, isMainThread());
        }
    }
    //endregion

    //region isMainThread isRegistered
    /**
     * Checks if the current thread is running in the main thread.
     * If there is no main thread support (e.g. non-Android), "true" is always returned. In that case MAIN thread
     * subscribers are always called in posting thread, and BACKGROUND subscribers are always called from a background
     * poster.
     */
    private boolean isMainThread() {
        return mainThreadSupport == null || mainThreadSupport.isMainThread();
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }
    //endregion

    //region unregister unsubscribeByEventType
    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber. */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        // 获取事件类的所有订阅信息列表，将订阅信息从订阅信息集合中移除，同时将订阅信息中的active属性置为FALSE
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    // 将订阅信息激活状态置为FALSE
                    subscription.active = false;
                    // 将订阅信息从集合中移除
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes. */
    public synchronized void unregister(Object subscriber) {
        // 获取订阅对象的所有订阅事件类列表
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                // 将订阅者的订阅信息移除
                unsubscribeByEventType(subscriber, eventType);
            }
            // 将订阅者从列表中移除
            typesBySubscriber.remove(subscriber);
        } else {
            logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }
    //endregion

    //region  post
    /** Posts the given event to the event bus. */
    public void post(Object event) {
        // currentPostingThreadState 是一个 ThreadLocal，
        // 他的特点是获取当前线程一份独有的变量数据，不受其他线程影响。
        // 这个在 Handler 里面有过源码分析
        PostingThreadState postingState = currentPostingThreadState.get();
        // postingState 就是获取到的线程独有的变量数据
        List<Object> eventQueue = postingState.eventQueue;
        // 把 post 的事件添加到事件队列
        eventQueue.add(event);
        // 如果没有处在事件发布状态，那么开始发送事件并一直保持发布状态
        if (!postingState.isPosting) {
            // 是否是主线程
            postingState.isMainThread = isMainThread();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }
    //endregion

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    //region postSticky
    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     */
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }
    //endregion

    //region hasSubscriberForEvent
    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
    //endregion

    //region postSingleEvent postSingleEventForEventType postToSubscription
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        // 得到事件的Class
        Class<?> eventClass = event.getClass();
        // 是否找到订阅者
        boolean subscriptionFound = false;
        // 如果支持事件继承，默认为支持
        if (eventInheritance) {
            // 查找 eventClass 的所有父类和接口，包括他自己
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                // 依次向 eventClass 的父类或接口的订阅方法发送事件
                // 只要有一个事件发送成功，返回 true ，那么 subscriptionFound 就为 true
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            // 发送事件
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        // 如果没有订阅者
        if (!subscriptionFound) {
            if (logNoSubscriberMessages) {
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            // 得到Subscription 列表
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            // 遍历 subscriptions
            for (Subscription subscription : subscriptions) {
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted;
                try {
                    // 发送事件
                    postToSubscription(subscription, event, postingState.isMainThread);
                    // 是否被取消了
                    aborted = postingState.canceled;
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                // 如果被取消，则跳出循环
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        // 根据不同的线程模式执行对应
        switch (subscription.subscriberMethod.threadMode) {
            // 和发送事件处于同一个线程
            case POSTING:
                invokeSubscriber(subscription, event);
                break;
            // 主线程
            case MAIN:
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            // 主线程
            case MAIN_ORDERED:
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, event);
                }
                break;
            // 子线程
            case BACKGROUND:
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    invokeSubscriber(subscription, event);
                }
                break;
            // 和发送事件处于不同的线程
            case ASYNC:
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }
    //endregion

    //region 查找event的父类及父接口，存放到eventTypesCache包括他自己
    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces. */
    //查找父类和接口
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            //找到缓存里是否有
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                //如果就没有，就新建一个
                eventTypes = new ArrayList<>();
                //从他自己开始，往上层一个一个加
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    //添加类
                    eventTypes.add(clazz);
                    //添加接口
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    //查找父类
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    //递归调用
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                //递归调用
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }
    //endregion

    //region invokeSubscriber 触发事件响应
    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);//todo
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    final static class PostingThreadState {
        final List<Object> eventQueue = new ArrayList<>();
        boolean isPosting;
        boolean isMainThread;
        Subscription subscription;
        Object event;
        boolean canceled;
    }
    //endregion

    //region ok
    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
    //endregion
}
