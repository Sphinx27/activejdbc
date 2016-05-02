/*
Copyright 2009-2015 Igor Polevoy

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/


package org.javalite.instrumentation;

import java.util.ArrayList;
import java.util.List;
import javassist.*;
import javassist.bytecode.SignatureAttribute;

/**
 * @author Igor Polevoy
 * @author Eric Nielsen
 */
public class ModelInstrumentation {

    private final CtClass modelClass;

    public ModelInstrumentation() throws NotFoundException {
        ClassPool cp = ClassPool.getDefault();
        cp.insertClassPath(new ClassClassPath(this.getClass()));
        this.modelClass = cp.get("org.javalite.activejdbc.Model");
    }

    public byte[] instrument(CtClass target) throws InstrumentationException {

        try {
            doInstrument(target);
            return target.toBytecode();
        } catch (Exception e) {
            throw new InstrumentationException(e);
        }
    }

    private void doInstrumentFromClass(CtClass target,CtClass source) throws NotFoundException, CannotCompileException {
        CtMethod[] modelMethods = source.getDeclaredMethods();
        CtMethod[] targetMethods = target.getDeclaredMethods();

        CtMethod newGetClass = null;
        CodeConverter conv = new CodeConverter();
        
        if (source.equals(modelClass)){
            CtMethod modelGetClass = source.getDeclaredMethod("modelClass");
            newGetClass = CtNewMethod.copy(modelGetClass, target, null);
            newGetClass.setBody("{ return " + target.getName() + ".class; }");

            // convert Model.getDaClass() calls to Target.getDaClass() calls
            conv.redirectMethodCall(modelGetClass, newGetClass);
        }
        
        // do not convert Model class to Target class in methods
        ClassMap classMap = null;
        //classMap = new ClassMap();
       // classMap.fix(source);

        for (CtMethod method : modelMethods) {
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                if (targetHasMethod(targetMethods, method)) {
                    Instrumentation.log("Detected method: " + method.getName() + ", skipping delegate.");
                } else {
                    CtMethod newMethod;
                    if ((Modifier.isProtected(modifiers) || Modifier.isPublic(modifiers)) && !"modelClass".equals(method.getName()) ) {
                        newMethod = CtNewMethod.copy(method, target, classMap);
                        newMethod.instrument(conv);
                    } else if ("modelClass".equals(method.getName())) {
                        if (newGetClass!=null){
                            newMethod = newGetClass;
                        } else {
                            newMethod = CtNewMethod.copy(method, target, classMap);
                        }
                    } else {
                        newMethod = CtNewMethod.delegator(method, target);
                    }

                    // Include the generic signature
                    for (Object attr : method.getMethodInfo().getAttributes()) {
                        if (attr instanceof SignatureAttribute) {
                            newMethod.getMethodInfo().addAttribute((SignatureAttribute) attr);
                        }
                    }
                    target.addMethod(newMethod);
                }
            }
        }
    }

    private boolean targetHasMethod(CtMethod[] targetMethods, CtMethod delegate) {
        for (CtMethod targetMethod : targetMethods) {
            if (targetMethod.equals(delegate)) {
                return true;
            }
        }
        return false;
    }

    private void doInstrument(CtClass target) throws NotFoundException, CannotCompileException {
        CtClass[] sources = getInstrumentationSources(target);
        for (CtClass source: sources){
            doInstrumentFromClass(target, source);
        }
    }

    private CtClass[] getInstrumentationSources(CtClass target) throws NotFoundException {
        List<CtClass> sources = new ArrayList<>();
        CtClass sc;
        do{
            sc = target.getSuperclass();

            if( Modifier.isAbstract(sc.getModifiers())){
                sources.add(sc);
            }
            target = sc;
        } while(!sc.equals(modelClass));
        return sources.toArray(new CtClass[0]);
    }

}
