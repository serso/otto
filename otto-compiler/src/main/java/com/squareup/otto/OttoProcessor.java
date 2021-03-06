/*
 * Copyright (C) 2016 Sergey Solovyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squareup.otto;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.validation.constraints.NotNull;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.*;

/**
 * <p>
 * Annotation processor which generates a single class which provides event handlers according to {@link Subscribe}
 * annotation and event producers according to {@link Produce} annotation.
 * </p>
 * <p>
 * This processor can generate {@link HandlerFinder} of two types: 'anonymous' where no reflection is used and 'reflective'
 * where actual method calls are done via reflection.<br/>
 * The downside of 'anonymous' approach is that for each subscriber's method an anonymous class is generated.<br/>
 * 'Reflective' event handlers use reflection for delivering events, however, the lookup is done in a constant time by
 * function's name and event type.<br/>
 * By default, 'reflective' event handlers are generated, to change this use '-Aotto.generate' javac option
 * ('-Aotto.generate=anonymous' for anonymous and '-Aotto.generate=reflective' for reflective)
 * </p>
 *
 * @author Sergey Solovyev
 */
public final class OttoProcessor extends AbstractProcessor {

  private static final byte[] BUFFER = new byte[4 * 1024];
  private static final Set<String> ANNOTATIONS = new HashSet<String>();
  private static final Set<String> OPTIONS = new HashSet<String>();
  private static final String OPTION_GENERATE = "otto.generate";

  static {
    ANNOTATIONS.add(Subscribe.class.getName());
    OPTIONS.add(OPTION_GENERATE);
  }

  @NotNull
  private Filer filer;
  @NotNull
  private Messager messager;
  @NotNull
  private Map<TypeElement, Map<TypeMirror, List<ExecutableElement>>> methodsInClass = new HashMap<TypeElement, Map<TypeMirror, List<ExecutableElement>>>();
  private boolean anonymous;

  public OttoProcessor() {
  }

  @NotNull
  public static TypeElement findEnclosingTypeElement(@NotNull final Element e) {
    Element candidate = e.getEnclosingElement();
    while (candidate != null && !(candidate instanceof TypeElement)) {
      candidate = candidate.getEnclosingElement();
    }
    if (candidate == null) {
      return null;
    }
    return TypeElement.class.cast(candidate);
  }

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    filer = env.getFiler();
    messager = env.getMessager();
    methodsInClass.clear();
    final Map<String, String> options = env.getOptions();
    final String generateOption = options.get(OPTION_GENERATE);
    if (generateOption == null) {
      anonymous = false;
    } else if ("anonymous".equals(generateOption)) {
      anonymous = true;
    } else if ("reflective".equals(generateOption)) {
      anonymous = false;
    } else {
      throw new IllegalArgumentException("Invalid value for 'otto.generate'. Expected: 'anonymous' or 'reflective', got: " + generateOption);
    }
    info("OttoProcessor#init");
  }

  @Override
  public boolean process(@NotNull Set<? extends TypeElement> annotations, @NotNull RoundEnvironment env) {
    info("OttoProcessor#process.start");
    if (env.processingOver()) {
      info("OttoProcessor#processingOver");
      return true;
    }
    try {
      final Map<TypeElement, Map<TypeMirror, List<ExecutableElement>>> methods = collectMethods(env);
      if (!methods.isEmpty()) {
        methodsInClass.putAll(methods);
        writeClasses(generateClass(methodsInClass));
      }
    } catch (ProcessingException e) {
      error(e.getMessage());
      return true;
    }
    info("OttoProcessor#process.end: classes processed = " + methodsInClass.size());
    return false;
  }

  @NotNull
  private TypeSpec generateClass(@NotNull Map<TypeElement, Map<TypeMirror, List<ExecutableElement>>> methodsByClass) {
    return TypeSpec.classBuilder("GeneratedHandlerFinder")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(HandlerFinder.class)
            .addMethod(generateFindAllProducers())
            .addMethod(generateFindAllSubscribers(methodsByClass))
            .addMethod(generateLookupMethod())
            .build();
  }

  @NotNull
  private MethodSpec generateLookupMethod() {
    return MethodSpec.methodBuilder("lookupMethod")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(Class.class, "type")
            .addParameter(String.class, "methodName")
            .addParameter(Class.class, "eventType")
            .addCode(
                    CodeBlock.builder().add("try {\n" +
                            "    return type.getDeclaredMethod(methodName, eventType);\n" +
                            "} catch ($T e) {\n" +
                            "    throw new $T(e);\n" +
                            "}\n", NoSuchMethodException.class, IllegalArgumentException.class).build())
            .returns(Method.class)
            .build();
  }

  @NotNull
  private MethodSpec generateFindAllSubscribers(@NotNull Map<TypeElement, Map<TypeMirror, List<ExecutableElement>>> methodsByClass) {
    final MethodSpec.Builder builder = MethodSpec.methodBuilder("findAllSubscribers")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(Object.class, "listener", Modifier.FINAL)
            .returns(Map.class);
    for (Map.Entry<TypeElement, Map<TypeMirror, List<ExecutableElement>>> entry : methodsByClass.entrySet()) {
      builder.addCode(generateSubscriber(entry.getKey(), entry.getValue())).addCode("\n");
    }
    builder.addStatement("throw new IllegalArgumentException(\"Object with class name \" + $N.getClass() + \" is not supported\")", "listener");
    return builder.build();
  }

  @NotNull
  private CodeBlock generateSubscriber(@NotNull TypeElement type, @NotNull Map<TypeMirror, List<ExecutableElement>> methodsByEventType) {
    final CodeBlock.Builder builder = CodeBlock.builder()
            .beginControlFlow("if (listener.getClass().equals($T.class))", type)
            .addStatement("final $T<$T<?>, $T<$T>> handlers = new $T<$T<?>, $T<$T>>($L)", Map.class, Class.class, Set.class, EventHandler.class, HashMap.class, Class.class, Set.class, EventHandler.class, methodsByEventType.size());
    for (Map.Entry<TypeMirror, List<ExecutableElement>> entry : methodsByEventType.entrySet()) {
      final TypeMirror eventType = entry.getKey();
      final List<ExecutableElement> methods = entry.getValue();
      builder.addStatement("handlers.put($T.class, $L)", eventType, generateEventHandlers(type, eventType, methods));
    }
    builder.addStatement("return handlers").endControlFlow();
    return builder.build();
  }

  @NotNull
  private CodeBlock generateEventHandlers(@NotNull TypeElement type, @NotNull TypeMirror eventType, @NotNull List<ExecutableElement> methods) {
    final CodeBlock.Builder builder = CodeBlock.builder();
    if (methods.size() > 1) {
      final CodeBlock handlersList = generateEventHandlersList(type, eventType, methods);
      builder.add("new $T<$T>($T.asList($L))", HashSet.class, EventHandler.class, Arrays.class, handlersList);
    } else {
      final CodeBlock handler = generateHandler(type, eventType, methods.get(0));
      builder.add("$T.<$T>singleton($L)", Collections.class, EventHandler.class, handler);
    }
    return builder.build();
  }

  @NotNull
  private CodeBlock generateEventHandlersList(@NotNull TypeElement type, @NotNull TypeMirror eventType, List<ExecutableElement> methods) {
    final CodeBlock.Builder builder = CodeBlock.builder();
    for (int i = 0; i < methods.size(); i++) {
      final ExecutableElement method = methods.get(i);
      if (i != 0) {
        builder.add(",");
      }
      builder.add(generateHandler(type, eventType, method));
    }
    return builder.build();
  }

  @NotNull
  private MethodSpec generateFindAllProducers() {
    return MethodSpec.methodBuilder("findAllProducers")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(Object.class, "listener", Modifier.FINAL)
            .returns(Map.class)
            .addStatement("return $T.emptyMap()", Collections.class)
            .build();
  }

  @NotNull
  private CodeBlock generateHandler(@NotNull TypeElement type, @NotNull TypeMirror eventType, @NotNull ExecutableElement method) {
    if (anonymous) {
      return CodeBlock.builder().add("\nnew $L(listener){public void handleEvent(Object event){(($T)listener).$N(($T)event);}}", "GeneratedEventHandler", type, method.getSimpleName(), eventType).build();
    } else {
      return CodeBlock.builder().add("\nnew ReflectiveEventHandler(listener, lookupMethod($T.class, $S, $T.class))", type, method.getSimpleName(), eventType).build();
    }
  }

  @NotNull
  private Map<TypeElement, Map<TypeMirror, List<ExecutableElement>>> collectMethods(@NotNull RoundEnvironment env) throws ProcessingException {
    final Map<TypeElement, Map<TypeMirror, List<ExecutableElement>>> methodsByClass = new HashMap<TypeElement, Map<TypeMirror, List<ExecutableElement>>>();
    for (Element e : env.getElementsAnnotatedWith(Subscribe.class)) {
      // annotation must present only in methods
      if (e.getKind() != ElementKind.METHOD) {
        throw new ProcessingException(e.getSimpleName() + " is annotated with @Subscribe but is not a method");
      }
      final ExecutableElement method = (ExecutableElement) e;
      if (anonymous) {
        // methods must be public as generated code will call it directly
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
          throw new ProcessingException("Method is not public: " + method.getSimpleName());
        }
      }
      final List<? extends VariableElement> parameters = method.getParameters();
      // there must be only one parameter
      if (parameters == null || parameters.size() == 0) {
        throw new ProcessingException("Too few arguments in: " + method.getSimpleName());
      }
      if (parameters.size() > 1) {
        throw new ProcessingException("Too many arguments in: " + method.getSimpleName());
      }
      if (anonymous) {
        // method shouldn't throw checked exceptions
        final List<? extends TypeMirror> exceptions = method.getThrownTypes();
        if (exceptions != null && exceptions.size() > 0) {
          throw new ProcessingException("Method shouldn't throw exceptions: " + method.getSimpleName());
        }
      }
      final TypeElement type = findEnclosingTypeElement(e);
      // class should exist
      if (type == null) {
        throw new ProcessingException("Could not find a class for " + method.getSimpleName());
      }
      // and it should be public
      if (!type.getModifiers().contains(Modifier.PUBLIC)) {
        throw new ProcessingException("Class is not public: " + type);
      }
      // as sell as all parent classes
      TypeElement parentType = findEnclosingTypeElement(type);
      while (parentType != null) {
        if (!parentType.getModifiers().contains(Modifier.PUBLIC)) {
          throw new ProcessingException("Class is not public: " + parentType);
        }
        parentType = findEnclosingTypeElement(parentType);
      }
      final VariableElement event = parameters.get(0);
      final TypeMirror eventType = event.asType();

      Map<TypeMirror, List<ExecutableElement>> methodsInClass = methodsByClass.get(type);
      if (methodsInClass == null) {
        methodsInClass = new HashMap<TypeMirror, List<ExecutableElement>>();
        methodsByClass.put(type, methodsInClass);
      }
      List<ExecutableElement> methodsByType = methodsInClass.get(eventType);
      if (methodsByType == null) {
        methodsByType = new ArrayList<ExecutableElement>();
        methodsInClass.put(eventType, methodsByType);
      }
      methodsByType.add(method);
    }
    return methodsByClass;
  }

  private void writeClasses(@NotNull TypeSpec handleFinderSpec) throws ProcessingException {
    writeHandlerFinder(handleFinderSpec);
    writeEventHandler();
  }

  private void writeEventHandler() throws ProcessingException {
    InputStream from = null;
    OutputStream to = null;
    JavaFileObject file = null;
    try {
      from = OttoProcessor.class.getResourceAsStream("/com/squareup/otto/GeneratedEventHandler.java");
      file = filer.createSourceFile(getPackageName() + ".GeneratedEventHandler");
      to = file.openOutputStream();
      copy(from, to);
      file = null;
    } catch (IOException e) {
      throw new ProcessingException(e);
    } finally {
      close(from);
      close(to);
      if (file != null) {
        file.delete();
      }
    }
  }

  public static long copy(@NotNull InputStream from, @NotNull OutputStream to) throws IOException {
    long total = 0;
    while (true) {
      final int r = from.read(BUFFER);
      if (r == -1) {
        break;
      }
      to.write(BUFFER, 0, r);
      total += r;
    }
    return total;
  }

  private void close(@NotNull Closeable c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (IOException e) {
      error(e.getMessage());
    }
  }

  private void writeHandlerFinder(@NotNull TypeSpec spec) throws ProcessingException {
    final JavaFile file = JavaFile.builder(getPackageName(), spec).build();
    try {
      file.writeTo(filer);
    } catch (IOException e) {
      throw new ProcessingException(e);
    }
  }

  @NotNull
  private String getPackageName() {
    return Bus.class.getPackage().getName();
  }

  private void error(@NotNull String msg) {
    messager.printMessage(Diagnostic.Kind.WARNING, msg);
  }

  private void info(@NotNull String msg) {
    messager.printMessage(Diagnostic.Kind.NOTE, msg);
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public Set<String> getSupportedOptions() {
    return OPTIONS;
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return ANNOTATIONS;
  }

  private static final class ProcessingException extends Exception {

    public ProcessingException(String message) {
      super(message);
    }

    public ProcessingException(Throwable cause) {
      super(cause);
    }
  }
}
