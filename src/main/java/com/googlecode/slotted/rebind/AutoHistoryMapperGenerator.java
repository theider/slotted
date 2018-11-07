package com.googlecode.slotted.rebind;

import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.ext.BadPropertyValueException;
import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.place.shared.Place;
import com.google.gwt.place.shared.PlaceTokenizer;
import com.google.gwt.place.shared.Prefix;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import com.googlecode.slotted.client.AutoHistoryMapper;
import com.googlecode.slotted.client.CacheActivities;
import com.googlecode.slotted.client.CodeSplit;
import com.googlecode.slotted.client.ScanPackages;

public class AutoHistoryMapperGenerator extends Generator {
    private static String NamePostfix = "Gen";

    public String generate(TreeLogger logger, GeneratorContext context, String typeName)
            throws UnableToCompleteException
    {
        TypeOracle typeOracle = context.getTypeOracle();
        JClassType clazz = typeOracle.findType(typeName);

        if (clazz == null) {
            throw new UnableToCompleteException();
        }

        try {

            SourceWriter sourceWriter = getSourceWriter(clazz, context, logger);
            if (sourceWriter != null) {
                writeInitMethod(logger, context, typeOracle, sourceWriter, clazz);

                sourceWriter.commit(logger);
                logger.log(TreeLogger.DEBUG, "Done Generating source for "
                        + clazz.getName(), null);
            }

        } catch (NotFoundException e) {
            logger.log(TreeLogger.ERROR, "Error Generating source for " + typeName, e);
            throw new UnableToCompleteException();
        }

        return clazz.getQualifiedSourceName() + NamePostfix;
    }

    public SourceWriter getSourceWriter(JClassType classType, GeneratorContext context, TreeLogger logger) {

        String packageName = classType.getPackage().getName();
        String simpleName = classType.getSimpleSourceName() + NamePostfix;
        ClassSourceFileComposerFactory composer =
                new ClassSourceFileComposerFactory(packageName, simpleName);

        String implementName = AutoHistoryMapper.class.getName();
        composer.setSuperclass(implementName);
        composer.addImport(GWT.class.getCanonicalName());
        composer.addImport(PlaceTokenizer.class.getCanonicalName());

        PrintWriter printWriter = context.tryCreate(logger, packageName,simpleName);

        if (printWriter == null) {
            return null;
        } else {
            SourceWriter sw = composer.createSourceWriter(context, printWriter);
            return sw;
        }

    }

    private void writeInitMethod(TreeLogger logger, GeneratorContext context, TypeOracle typeOracle,
            SourceWriter sourceWriter, JClassType clazz) throws NotFoundException, UnableToCompleteException
    {
        JClassType placeType = typeOracle.getType(Place.class.getName());
        JClassType tokenizerType = typeOracle.getType(PlaceTokenizer.class.getName());

        sourceWriter.println("protected void init() {");
        sourceWriter.indent();

        JClassType[] types = typeOracle.getTypes();
        List<String> scanPackages = getScanPackages(context, clazz);

        for (JClassType place: types) {
            if (!place.isAbstract() && place.isDefaultInstantiable() &&
                    place.isAssignableTo(placeType) && isInScanPackages(place, scanPackages))
            {
                JClassType tokenizer = getTokenizer(place, tokenizerType);
                String prefix = getPrefix(place, tokenizer);
                String placeActivitiesToCache = getPlaceActivitiesToCache(place);
                String codeSplitMapper = getCodeSplitMapper(place);

                String tokenizerParam;
                if (tokenizer != null) {
                    tokenizerParam = "(PlaceTokenizer) GWT.create(" +
                            tokenizer.getQualifiedSourceName() + ".class)";
                } else {
                    String autoTokenizer = new AutoTokenizerGenerator().generate(logger, context,
                            place.getQualifiedSourceName());
                    tokenizerParam = "(PlaceTokenizer) GWT.create(" + autoTokenizer + ".class)";
                }

                sourceWriter.println("registerPlace(" + place.getQualifiedSourceName() +
                        ".class, " + prefix + ", " + tokenizerParam + ", " +
                        placeActivitiesToCache + ", " + codeSplitMapper + ");");
            }
        }

        sourceWriter.outdent();
        sourceWriter.println("}");
    }

    private List<String> getScanPackages(GeneratorContext context, JClassType clazz) {
        ScanPackages scanAnnotation = clazz.getAnnotation(ScanPackages.class);
        if (scanAnnotation != null) {
            return Arrays.asList(scanAnnotation.value());
        }

        try {
            return context.getPropertyOracle().getConfigurationProperty("slotted.place.scan.package").getValues();
        } catch (BadPropertyValueException e) {
            return null;
        }

    }

    private boolean isInScanPackages(JClassType place, List<String> scanPackages) {
        if (scanPackages == null || scanPackages.isEmpty()) {
            return true;

        } else {
            String className = place.getQualifiedSourceName();
            for (String packageString: scanPackages) {
                if (className.startsWith(packageString)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JClassType getTokenizer(JClassType place, JClassType tokenizerType) {
        JClassType[] nestedTypes = place.getNestedTypes();
        JClassType tokenizer = null;
        for (JClassType nestedType: nestedTypes) {
            if (nestedType.isAssignableTo(tokenizerType)) {
                tokenizer = nestedType;
                break;
            }
        }
        return tokenizer;
    }

    private String getPrefix(JClassType place, JClassType tokenizer) {
        String prefix = null;
        if (tokenizer != null) {
            prefix = findPrefix(tokenizer);
        }
        if (prefix == null) {
            prefix = findPrefix(place);
        }

        return prefix;
    }

    private String findPrefix(JClassType type) {
        Annotation[] annotations = type.getAnnotations();
        for (Annotation annotation: annotations) {
            if (annotation instanceof Prefix) {
                Prefix prefixAnnotation = (Prefix) annotation;
                return "\"" + prefixAnnotation.value() + "\"";
            }
        }
        return null;
    }

    private String getPlaceActivitiesToCache(JClassType place) {
        for (Annotation annotation: place.getAnnotations()) {
            if (annotation instanceof CacheActivities) {
                StringBuilder cacheArray = new StringBuilder();
                cacheArray.append("new Class[]{");
                for (Class placeClass: ((CacheActivities) annotation).value()) {
                    cacheArray.append(placeClass.getCanonicalName());
                    cacheArray.append(".class,");

                }
                cacheArray.deleteCharAt(cacheArray.length() - 1);
                cacheArray.append("}");
                return cacheArray.toString();
            }
        }
        return "null";
    }

    private String getCodeSplitMapper(JClassType place) {
        CodeSplit annotation = place.getAnnotation(CodeSplit.class);
        if (annotation != null) {
            Class mapperClass = ((CodeSplit) annotation).value();
            return mapperClass.getCanonicalName() + ".class";
        } else {
            return "null";
        }
    }
}
