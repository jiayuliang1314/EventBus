/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    //region 参数
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    //subscriberClass 方法
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];
    //endregion

    //region ok 构造函数
    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }
    //endregion

    //region ok findSubscriberMethods
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //先从缓存里面读取，订阅者的 Class
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        // ignoreGeneratedIndex属性表示是否忽略注解器生成的MyEventBusIndex。
        // ignoreGeneratedIndex的默认值为false，可以通过EventBusBuilder来设置它的值
        if (ignoreGeneratedIndex) {
            // 利用反射来获取订阅类中所有订阅方法信息
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            // 从注解器生成的MyEventBusIndex类中获得订阅类的订阅方法信息
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }
    //endregion

    //region findUsingInfo
    //从注解器生成的MyEventBusIndex类中获得订阅类的订阅方法信息 // 使用apt提前解析的订阅者信息
    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        // FindState 涉及到 享元设计模式
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            //
            findState.subscriberInfo = getSubscriberInfo(findState);
            if (findState.subscriberInfo != null) {
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                findUsingReflectionInSingleClass(findState);
            }
            findState.moveToSuperclass();
        }
        // 释放 findState 享元模式
        return getMethodsAndRelease(findState);
    }

    private SubscriberInfo getSubscriberInfo(FindState findState) {
        //看样子getSuperSubscriberInfo会一直为null
        //不太懂这里 todo
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }
    //endregion



    //region ok getMethodsAndRelease prepareFindState clearCaches
    //享元模式本质就是将大量的相似的对象的公共的不会变化的部分抽象出来，作为静态变量，作为全局唯一的对象，让所有的对象共同使用这一组对象，达到节约内存的目的。
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        findState.recycle();
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }
    //endregion



    // 利用反射来获取订阅类中所有订阅方法信息
    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            // 寻找某个类中的所有事件响应方法
            findUsingReflectionInSingleClass(findState);
            findState.moveToSuperclass();//继续寻找当前类父类中注册的事件响应方法
        }
        return getMethodsAndRelease(findState);
    }

    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            try {
                // 通过反射来获取订阅类的所有方法
                methods = findState.clazz.getMethods();
            } catch (LinkageError error) { // super class of NoClassDefFoundError to be a bit more broad...
                String msg = "Could not inspect methods of " + findState.clazz.getName();
                if (ignoreGeneratedIndex) {
                    msg += ". Please consider using EventBus annotation processor to avoid reflection.";
                } else {
                    msg += ". Please make this class visible to EventBus annotation processor to avoid reflection.";
                }
                throw new EventBusException(msg, error);
            }
            findState.skipSuperClasses = true;
        }
        // for 循环所有方法
        for (Method method : methods) {
            // 获取方法访问修饰符
            int modifiers = method.getModifiers();
            //  找到所有声明为 public 的方法
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                Class<?>[] parameterTypes = method.getParameterTypes();// 获取参数的的 Class
                if (parameterTypes.length == 1) {// 只允许包含一个参数
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        // 获取事件的 Class ，也就是方法参数的 Class
                        Class<?> eventType = parameterTypes[0];
                        // 检测添加
                        if (findState.checkAdd(method, eventType)) {
                            // 获取 ThreadMode
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            // 往集合里面添加 SubscriberMethod ，解析方法注解所有的属性
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }



    static class FindState {
        //region 参数
        //订阅方法集合
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        //eventtype对应的方法
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        //methdkey对应的订阅者
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        //当前订阅者
        Class<?> subscriberClass;
        //当前查找的类
        Class<?> clazz;//父类，初始化的时候就是这个类
        //是否跳过父类查找
        boolean skipSuperClasses;
        SubscriberInfo subscriberInfo;
        //endregion

        //region initForSubscriber recycle
        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }
        //endregion

        //region checkAdd()方法用来判断FindState中是否已经添加过将该事件类型为key的键值对，没添加过则返回true
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.

            // 1.检查eventType是否已经注册过对应的方法（一般都没有）
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
//                第一种是判断当前类中是否已经有这个EventType和对应的订阅方法，一般一个类不会有对同
//                一个EventType写多个方法，会直接返回true，进行保存。
                //如果没有，则返回true
                return true;
            } else {

                // 2. 如果已经有方法注册了这个eventType
//                但是如果出现了同一个类中同样的EventType写了多个方法，该如何处理？

                //a.1 假如注册了3个方法，方法名不一样afun1 afun2 afun3
                //a.7 afun3 的时候existing 对象不是method了
                //b.1 子类bfun_child，父类也注册了bfun_parent
                if (existing instanceof Method) {
                    //这里步骤的意义在于往subscriberClassByMethodKey map里加入第一个方法
                    //a.2 传入以前的方法afun1
                    //b.2 传入以前的方法bfun_child
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    //a.4 anyMethodByEventType eventType设置了个不是Method的对象
                    //b.4 anyMethodByEventType eventType设置了个不是Method的对象
                    anyMethodByEventType.put(eventType, this);
                }
                //a.5 传入现在的方法afun2
                //a.8 传入现在的方法afun3
                //b.5 传入现在的方法bfun_parent
                return checkAddWithMethodSignature(method, eventType);

            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            // 以[方法名>eventType]为Key
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            String methodKey = methodKeyBuilder.toString();

            // 拿到新的订阅方法所属类
            Class<?> methodClass = method.getDeclaringClass();//定义的类，可能是父类
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);

            //a.3 传入以前的方法afun1,methodClassOld为null,并保存到了subscriberClassByMethodKey afun1->key，返回true
            //b.3 传入以前的方法bfun_child,methodClassOld为null,并保存到了subscriberClassByMethodKey bfun_child->key，返回true

            //a.6 传入现在的方法afun2，methodClassOld为null，并保存到了subscriberClassByMethodKey afun2->key，返回true
            //b.6 传入现在的方法bfun_parent，methodClassOld为子类，methodClass为父类
            //a.8 传入现在的方法afun3，methodClassOld为null，并保存到了subscriberClassByMethodKey afun3->key，返回true
//            对于同一类中同样的EventType写了多个方法，因为方法名不同，所以[方法名>eventType]的Key不同，
//            methodClassOld会为null，直接返回 true。所以这种情况会将所有相同EventType的方法都进行保存。
//            对于子类重写父类方法的情况，则methodClassOld（即子类）不为null,并且methodClassOld也不是methodClass的父类，
//            所以会返回false。即对于子类重写父类订阅方法，只会保存子类的订阅方法，忽略父类的订阅方法。

//            isAssignableFrom()方法是判断是否为某个类的父类，instanceof关键字是判断是否某个类的子类。
            //b.7 传入现在的方法bfun_parent，methodClassOld为子类，methodClass为父类
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
//                这次根据 方法名>参数名进行完整校验,因为同一个类中同名同参的函数是不存在的,而同名不同参的在前一步已经被过滤了，
//                所以这里确保在一个类中不会重复注册.
//                但如果子类重写父类的方法的话,就会出现相同的methodKey。这时EventBus会做一次验证,
//                并保留子类的订阅信息。由于扫描是由子类向父类的顺序，故此时应当保留methodClassOld而忽略methodClass。如果代码上的注释 Revert the put
                //b.8 传入现在的方法bfun_parent，methodClassOld为子类，methodClass为父类，subscriberClassByMethodKey保存子类的，返回false，不保存
                // Revert the put, old class is further down the class hierarchy
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }
        //endregion

        //region moveToSuperclass
        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                // Skip system classes, this degrades performance.
                // Also we might avoid some ClassNotFoundException (see FAQ for background).
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") ||
                        clazzName.startsWith("android.") || clazzName.startsWith("androidx.")) {
                    clazz = null;
                }
            }
        }
        //endregion
    }

}
