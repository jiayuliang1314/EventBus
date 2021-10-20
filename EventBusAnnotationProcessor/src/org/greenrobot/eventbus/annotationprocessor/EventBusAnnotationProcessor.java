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
package org.greenrobot.eventbus.annotationprocessor;

import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import de.greenrobot.common.ListMap;

import static net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.AGGREGATING;

/**
 * Is an aggregating processor as it writes a single file, the subscriber index file,
 * based on found elements with the @Subscriber annotation.
 */
@SupportedAnnotationTypes("org.greenrobot.eventbus.Subscribe")//Subscribe的注解
@SupportedOptions(value = {"eventBusIndex", "verbose"})//参数，可配置参数
@IncrementalAnnotationProcessor(AGGREGATING)//聚合，总计;合计aggregating
public class EventBusAnnotationProcessor extends AbstractProcessor {
    //region 参数
    //    annotationProcessorOptions {
//        arguments = [eventBusIndex: 'org.greenrobot.eventbusperf.MyEventBusIndex']
//    }
    ///Users/admin/StudioProjects/EventBus/EventBusPerformance/build/generated/ap_generated_sources/debug/out/org/greenrobot/eventbusperf/MyEventBusIndex.java
    public static final String OPTION_EVENT_BUS_INDEX = "eventBusIndex";//参数，文件位置
    public static final String OPTION_VERBOSE = "verbose";//冗长的，打印log

    /**
     * Found subscriber methods for a class (without superclasses).
     */
    // 自定义的数据结构，保存`<Key, List<Value>>`类型数据；这里的key是订阅类，List<Value>是方法
    private final ListMap<TypeElement, ExecutableElement> methodsByClass = new ListMap<>();
    // 保存不合法的元素，这里的key是订阅类
    private final Set<TypeElement> classesToSkip = new HashSet<>();

    private boolean writerRoundDone;//writerRoundDone，用于标记多次进入process异常情况
    private int round;//round过程，用于标记多次进入process异常情况
    private boolean verbose;//是否打印所有log
    //endregion

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {//TypeElement RoundEnvironment
        Messager messager = processingEnv.getMessager();
        try {
            String index = processingEnv.getOptions().get(OPTION_EVENT_BUS_INDEX);
            if (index == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, "No option " + OPTION_EVENT_BUS_INDEX +
                        " passed to annotation processor");
                return false;
            }
            verbose = Boolean.parseBoolean(processingEnv.getOptions().get(OPTION_VERBOSE));
            int lastPeriod = index.lastIndexOf('.');
            //package名字
            String indexPackage = lastPeriod != -1 ? index.substring(0, lastPeriod) : null;

            //region 检查annotations.isEmpty env.processingOver
            round++;
            if (verbose) {
                //处理round第几个回合，annotations不空，处理状态
                messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", new annotations: " +
                        !annotations.isEmpty() + ", processingOver: " + env.processingOver());
            }
            if (env.processingOver()) {//处理完了
                if (!annotations.isEmpty()) {//annotations不空
                    //报错
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Unexpected processing state: annotations still available after processing over");
                    return false;
                }
            }
            if (annotations.isEmpty()) {//如果annotations为空，返回
                return false;
            }
            //endregion

            if (writerRoundDone) {//如果处理完了，还进入这个流程，说明有问题
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Unexpected processing state: annotations still available after writing.");
            }
            // 取出所有订阅者信息
            collectSubscribers(annotations, env, messager);
            // 检查订阅者信息
            checkForSubscribersToSkip(messager, indexPackage);

            if (!methodsByClass.isEmpty()) {
                // 查找到的注解的方法不为空，生成Java文件
                createInfoIndexFile(index);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @Subscribe annotations found");
            }
            writerRoundDone = true;
        } catch (RuntimeException e) {
            // IntelliJ does not handle exceptions nicely, so log and print a message
            e.printStackTrace();
            messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected error in EventBusAnnotationProcessor: " + e);
        }
        return true;
    }

    //region collectSubscribers ok
    private void collectSubscribers(Set<? extends TypeElement> annotations, RoundEnvironment env, Messager messager) {
        // 遍历所有注解
        for (TypeElement annotation : annotations) {//TypeElement RoundEnvironment
            // 拿到被注解标记的所有元素
            //RoundEnvironment的getElementsAnnotatedWith方法， 这个方法会返回被当前注解标记的所有元素，可能是类、变量、方法等。
            Set<? extends Element> elements = env.getElementsAnnotatedWith(annotation);
            // 遍历所有元素
            for (Element element : elements) {//Element
                // 元素必须是方法，因为@Subscribe只能注解方法
                if (element instanceof ExecutableElement) {//ExecutableElement
                    ExecutableElement method = (ExecutableElement) element;
                    // 检查方法，条件：订阅方法必须是非静态的，公开的，参数只能有一个
                    if (checkHasNoErrors(method, messager)) {
                        // 取封装订阅方法的类
                        TypeElement classElement = (TypeElement) method.getEnclosingElement();
                        // 以类名为key，保存订阅方法
                        //在方法最后，将筛选出来的订阅者和方法都保存在了methodsByClass这个容器里，这个容器的数据结构是<KEY, <List<Value>>>，可以存储订阅类中的多个方法。
                        methodsByClass.putElement(classElement, method);
                    }
                } else {
                    messager.printMessage(Diagnostic.Kind.ERROR, "@Subscribe is only valid for methods", element);
                }
            }
        }
    }
    //endregion

    //region checkForSubscribersToSkip ok
    /**
     * Subscriber classes should be skipped if their class or any involved event class are not visible to the index.
     * checkForSubscribersToSkip 查找不合法的订阅者
     */
    /**
     * @param messager
     * @param myPackage 配置的myPackage
     */
    private void checkForSubscribersToSkip(Messager messager, String myPackage) {
        // 遍历所有订阅类
        for (TypeElement skipCandidate : methodsByClass.keySet()) {
            TypeElement subscriberClass = skipCandidate;
            //开启子类到父类的while循环检查
            while (subscriberClass != null) {
                // 订阅类必须是可访问的，否则记录并直接break，检查下一个订阅类
                if (!isVisible(myPackage, subscriberClass)) {
                    boolean added = classesToSkip.add(skipCandidate);
                    if (added) {
                        String msg;
                        if (subscriberClass.equals(skipCandidate)) {
                            msg = "Falling back to reflection because class is not public";
                        } else {
                            msg = "Falling back to reflection because " + skipCandidate +
                                    " has a non-public super class";
                        }
                        messager.printMessage(Diagnostic.Kind.NOTE, msg, subscriberClass);
                    }
                    break;
                }
                // 拿到订阅类的所有订阅方法
                List<ExecutableElement> methods = methodsByClass.get(subscriberClass);
                if (methods != null) {
                    // 检查所有订阅方法
                    for (ExecutableElement method : methods) {//ExecutableElement
                        String skipReason = null;
                        // 拿到订阅方法的参数，开始对参数进行检查
                        VariableElement param = method.getParameters().get(0);//VariableElement
                        // 取得参数(Event)的类型
                        //返回此元素定义的类型。返回值用TypeMirror表示，TypeMirror表示了Java编程语言中的类型，包括基本类型，一般用来做类型判断。
                        TypeMirror typeMirror = getParamTypeMirror(param, messager);
                        // 参数(Event)的类型必须是类或接口
                        if (!(typeMirror instanceof DeclaredType) ||
                                !(((DeclaredType) typeMirror).asElement() instanceof TypeElement)) {
                            skipReason = "event type cannot be processed";
                        }
                        if (skipReason == null) {
                            // 拿到参数(Event)元素
                            TypeElement eventTypeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
                            // 检查参数(Event)元素是不是可访问的
                            if (!isVisible(myPackage, eventTypeElement)) {
                                skipReason = "event type is not public";
                            }
                        }
                        if (skipReason != null) {
                            // 将不合格的订阅类记录下来
                            boolean added = classesToSkip.add(skipCandidate);
                            if (added) {
                                String msg = "Falling back to reflection because " + skipReason;
                                if (!subscriberClass.equals(skipCandidate)) {
                                    msg += " (found in super class for " + skipCandidate + ")";
                                }
                                messager.printMessage(Diagnostic.Kind.NOTE, msg, param);
                            }
                            break;
                        }
                    }
                }
                // 切换到父类，若父类是系统的类，则返回null，结束while循环
                subscriberClass = getSuperclass(subscriberClass);
            }
        }
    }

    private TypeMirror getParamTypeMirror(VariableElement param, Messager messager) {
        // 获取元素类型
        TypeMirror typeMirror = param.asType();
        // Check for generic type 检查泛型类型
        // 元素类型必须得是类或接口类型
        //你可以把DeclaredType （type）看作是一个类的泛型类型（例如List<String> ）;
        // 与基本上忽略泛型类型的TypeElement （element）相比（例如List ）。
        if (typeMirror instanceof TypeVariable) {//TypeVariable
            // 获取该类型变量的上边界，如果有extends，则返回父类，否则返回Object
            TypeMirror upperBound = ((TypeVariable) typeMirror).getUpperBound();
            // 是声明类型
            if (upperBound instanceof DeclaredType) {//DeclaredType
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.NOTE, "Using upper bound type " + upperBound +
                            " for generic parameter", param);
                }
                // 替换参数类型为上边界的类型
                typeMirror = upperBound;
            }
        }
        return typeMirror;
    }
    //endregion

    //region createInfoIndexFile ok

    /**
     * eventBusIndex index表示生成的MyEventBusIndex 类名+className
     *
     * @param index
     */
    private void createInfoIndexFile(String index) {
        BufferedWriter writer = null;
        try {
            // 通过编译环境的文件工具创建Java文件
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(index);

            // 截取出包名
            int period = index.lastIndexOf('.');
            String myPackage = period > 0 ? index.substring(0, period) : null;
            // 截取出类名
            String clazz = index.substring(period + 1);
            //开始向Java文件中写入代码
            writer = new BufferedWriter(sourceFile.openWriter());
            if (myPackage != null) {
                writer.write("package " + myPackage + ";\n\n");
            }
            writer.write("import org.greenrobot.eventbus.meta.SimpleSubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberMethodInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfoIndex;\n\n");
            writer.write("import org.greenrobot.eventbus.ThreadMode;\n\n");
            writer.write("import java.util.HashMap;\n");
            writer.write("import java.util.Map;\n\n");
            writer.write("/** This class is generated by EventBus, do not edit. */\n");
            writer.write("public class " + clazz + " implements SubscriberInfoIndex {\n");
            writer.write("    private static final Map<Class<?>, SubscriberInfo> SUBSCRIBER_INDEX;\n\n");
            writer.write("    static {\n");
            writer.write("        SUBSCRIBER_INDEX = new HashMap<Class<?>, SubscriberInfo>();\n\n");
            // 写入查找到的订阅者的相关代码
            writeIndexLines(writer, myPackage);
            writer.write("    }\n\n");
            writer.write("    private static void putIndex(SubscriberInfo info) {\n");
            writer.write("        SUBSCRIBER_INDEX.put(info.getSubscriberClass(), info);\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            writer.write("    public SubscriberInfo getSubscriberInfo(Class<?> subscriberClass) {\n");
            writer.write("        SubscriberInfo info = SUBSCRIBER_INDEX.get(subscriberClass);\n");
            writer.write("        if (info != null) {\n");
            writer.write("            return info;\n");
            writer.write("        } else {\n");
            writer.write("            return null;\n");
            writer.write("        }\n");
            writer.write("    }\n");
            writer.write("}\n");
        } catch (IOException e) {
            throw new RuntimeException("Could not write source for " + index, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    //Silent
                }
            }
        }
    }

    private void writeIndexLines(BufferedWriter writer, String myPackage) throws IOException {
        // 遍历methodsByClass
        for (TypeElement subscriberTypeElement : methodsByClass.keySet()) {//TypeElement
            // 跳过不合格的订阅者
            if (classesToSkip.contains(subscriberTypeElement)) {
                continue;
            }
            // 获取订阅类的字符串名称，格式是：包名.类名.class
            String subscriberClass = getClassString(subscriberTypeElement, myPackage);
            // 再一次检查是否可访问
            if (isVisible(myPackage, subscriberTypeElement)) {
                writeLine(writer, 2,
                        "putIndex(new SimpleSubscriberInfo(" + subscriberClass + ".class,",
                        "true,", "new SubscriberMethodInfo[] {");
                // 取出订阅类的所有订阅方法
                List<ExecutableElement> methods = methodsByClass.get(subscriberTypeElement);//ExecutableElement
                // 生成 [new 一个SubscriberMethodInfo，并将订阅方法的相关信息写入]ava的代码
                writeCreateSubscriberMethods(writer, methods, "new SubscriberMethodInfo", myPackage);
                writer.write("        }));\n\n");
            } else {
                writer.write("        // Subscriber not visible to index: " + subscriberClass + "\n");
            }
        }
    }

    //很简单，遍历查找到的所有订阅者，然后跳过不合法的订阅者，生成写入订阅者的代码。
    //显然，writeCreateSubscriberMethods方法中生成了写入订阅方法的代码：
    private void writeCreateSubscriberMethods(BufferedWriter writer, List<ExecutableElement> methods,
                                              String callPrefix, String myPackage) throws IOException {
        // 遍历订阅类中的所有订阅方法
        for (ExecutableElement method : methods) {//ExecutableElement
            // 获取方法参数
            List<? extends VariableElement> parameters = method.getParameters();//VariableElement
            // 取出第一个参数的参数镜像Mirror
            TypeMirror paramType = getParamTypeMirror(parameters.get(0), null);//TypeMirror
            // 通过参数类型拿到参数元素
            TypeElement paramElement = (TypeElement) processingEnv.getTypeUtils().asElement(paramType);//TypeElement
            // 方法名
            String methodName = method.getSimpleName().toString();
            // 参数类型 类名
            String eventClass = getClassString(paramElement, myPackage) + ".class";

            // 获取方法上的注解
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            List<String> parts = new ArrayList<>();
            parts.add(callPrefix + "(\"" + methodName + "\",");
            String lineEnd = "),";
            // 获取注解的值
            if (subscribe.priority() == 0 && !subscribe.sticky()) {
                if (subscribe.threadMode() == ThreadMode.POSTING) {
                    parts.add(eventClass + lineEnd);
                } else {
                    parts.add(eventClass + ",");
                    parts.add("ThreadMode." + subscribe.threadMode().name() + lineEnd);
                }
            } else {
                parts.add(eventClass + ",");
                parts.add("ThreadMode." + subscribe.threadMode().name() + ",");
                parts.add(subscribe.priority() + ",");
                parts.add(subscribe.sticky() + lineEnd);
            }
            // 生成代码
            writeLine(writer, 3, parts.toArray(new String[parts.size()]));

            if (verbose) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
                        method.getEnclosingElement().getSimpleName() + "." + methodName +
                        "(" + paramElement.getSimpleName() + ")");
            }
        }
    }
    //endregion

    //region ok
    private boolean checkHasNoErrors(ExecutableElement element, Messager messager) {//ExecutableElement
        if (element.getModifiers().contains(Modifier.STATIC)) {//Modifier
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must not be static", element);
            return false;
        }

        if (!element.getModifiers().contains(Modifier.PUBLIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must be public", element);
            return false;
        }

        List<? extends VariableElement> parameters = ((ExecutableElement) element).getParameters();//VariableElement
        if (parameters.size() != 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must have exactly 1 parameter", element);
            return false;
        }
        return true;
    }

    /**
     * 保证注解的方法是public方法或者和配置的OPTION_EVENT_BUS_INDEX eventBusIndex myPackage一个
     * <p>
     * 可以看到这个方法主要是对第一步查找到的订阅者和订阅方法进行检查，因为要生成的索引类中要访问订阅类和事件，
     * 所以必须要求订阅类和Event参数是可访问的，并且Event参数必须是类或接口类型，因为EventBus的订阅方法是不支持基本类型的。
     *
     * @param myPackage   配置的myPackage
     * @param typeElement
     * @return
     */
    private boolean isVisible(String myPackage, TypeElement typeElement) {//TypeElement
        // 获取修饰符
        Set<Modifier> modifiers = typeElement.getModifiers();
        boolean visible;
        if (modifiers.contains(Modifier.PUBLIC)) {//Modifier
            // public的直接return true
            visible = true;
        } else if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)) {
            // private和protected的直接return false
            visible = false;
        } else {
            // 获取元素所在包名
            String subscriberPackage = getPackageElement(typeElement).getQualifiedName().toString();
            if (myPackage == null) {
                // 是否都在最外层，没有包名
                visible = subscriberPackage.length() == 0;
            } else {
                // 是否在同一包下
                visible = myPackage.equals(subscriberPackage);
            }
        }
        return visible;
    }

    //得到TypeElement父类的TypeElement
    private TypeElement getSuperclass(TypeElement type) {//TypeElement
        if (type.getSuperclass().getKind() == TypeKind.DECLARED) {//DECLARED宣布
            TypeElement superclass = (TypeElement) processingEnv.getTypeUtils().asElement(type.getSuperclass());//TypeElement
            String name = superclass.getQualifiedName().toString();
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                return null;
            } else {
                return superclass;
            }
        } else {
            return null;
        }
    }

    private String getClassString(TypeElement typeElement, String myPackage) {//TypeElement
        PackageElement packageElement = getPackageElement(typeElement);//PackageElement
        String packageString = packageElement.getQualifiedName().toString();
        String className = typeElement.getQualifiedName().toString();
        if (packageString != null && !packageString.isEmpty()) {
            if (packageString.equals(myPackage)) {
                className = cutPackage(myPackage, className);
            } else if (packageString.equals("java.lang")) {//todo java.lang下有基本数据类型的封装类
                className = typeElement.getSimpleName().toString();
            }
        }
        return className;
    }

    private void writeLine(BufferedWriter writer, int indentLevel, String... parts) throws IOException {
        writeLine(writer, indentLevel, 2, parts);
    }

    private void writeLine(BufferedWriter writer, int indentLevel, int indentLevelIncrease, String... parts)
            throws IOException {
        writeIndent(writer, indentLevel);
        int len = indentLevel * 4;
        //依次写入parts数组里的元素
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i != 0) {
                if (len + part.length() > 118) {//一行如果超过118个字符先换行
                    writer.write("\n");
                    if (indentLevel < 12) {
                        indentLevel += indentLevelIncrease;
                    }
                    writeIndent(writer, indentLevel);
                    len = indentLevel * 4;
                } else {
                    writer.write(" ");
                }
            }
            writer.write(part);//是这里
            len += part.length();
        }
        writer.write("\n");
    }

    /**
     * 得到subscriber的PackageElement，用以获取包名
     *
     * @param subscriberClass
     * @return
     */
    private PackageElement getPackageElement(TypeElement subscriberClass) {//TypeElement PackageElement
        Element candidate = subscriberClass.getEnclosingElement();//把…围起来; 围住;
        while (!(candidate instanceof PackageElement)) {//往上找
            candidate = candidate.getEnclosingElement();
        }
        return (PackageElement) candidate;
    }

    /**
     * 得到类的classname
     *
     * @param paket
     * @param className
     * @return
     */
    private String cutPackage(String paket, String className) {
        if (className.startsWith(paket + '.')) {
            // Don't use TypeElement.getSimpleName, it doesn't work for us with inner classes
            return className.substring(paket.length() + 1);
        } else {
            // Paranoia
            throw new IllegalStateException("Mismatching " + paket + " vs. " + className);
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        //javax.lang.model.SourceVersion RELEASE_8
        return SourceVersion.latest();
    }

    //缩进indent
    private void writeIndent(BufferedWriter writer, int indentLevel) throws IOException {
        for (int i = 0; i < indentLevel; i++) {
            writer.write("    ");
        }
    }
    //endregion
}
