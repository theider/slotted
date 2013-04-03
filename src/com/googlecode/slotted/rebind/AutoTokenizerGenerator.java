package com.googlecode.slotted.rebind;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JField;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.place.shared.PlaceTokenizer;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import com.googlecode.slotted.client.GlobalParameter;
import com.googlecode.slotted.client.PlaceParameters;
import com.googlecode.slotted.client.SlottedPlace;
import com.googlecode.slotted.client.SlottedTokenizer;
import com.googlecode.slotted.client.TokenizerParameter;
import com.googlecode.slotted.client.TokenizerUtil;

import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.util.LinkedList;
import java.util.List;

public class AutoTokenizerGenerator extends Generator {
    private static String NamePostfix = "Tokenizer";

    public String generate(TreeLogger logger, GeneratorContext context, String typeName)
            throws UnableToCompleteException
    {
        try {
            TypeOracle typeOracle = context.getTypeOracle();
            JClassType placeType = getPlaceType(typeOracle, typeName);
            if (placeType == null) {
                throw new UnableToCompleteException();
            }

            LinkedList<JField> tokenParams = new LinkedList<JField>();
            LinkedList<JField> globalParams = new LinkedList<JField>();

            JField[] fields = placeType.getFields();
            for (JField field: fields) {
                for (Annotation annotation: field.getAnnotations()) {
                    if (annotation instanceof TokenizerParameter) {
                        tokenParams.add(field);
                        break;

                    } else if (annotation instanceof GlobalParameter) {
                        globalParams.add(field);
                    }
                }
            }

            SourceWriter sourceWriter = getSourceWriter(placeType, context, logger);
            if (sourceWriter != null) {
                writeAccessors(sourceWriter, tokenParams, placeType);
                writeAccessors(sourceWriter, globalParams, placeType);
                writeGlobalExtractor(sourceWriter, globalParams, placeType);
                writeGetToken(sourceWriter, tokenParams, placeType);
                writeGetPlace(sourceWriter, tokenParams, placeType);

                sourceWriter.commit(logger);
                logger.log(TreeLogger.DEBUG, "Done Generating source for " + placeType.getName(), null);
            }

            return placeType.getQualifiedSourceName() + NamePostfix;
        } catch (NotFoundException e) {
            logger.log(TreeLogger.ERROR, "Error Generating source for " + typeName, e);
            throw new UnableToCompleteException();
        }

    }

    private JClassType getPlaceType(TypeOracle typeOracle, String typeName)
            throws NotFoundException
    {
        JClassType placeType = null;
        JClassType placeBaseType = typeOracle.getType(SlottedPlace.class.getName());
        JClassType tokenizerBaseType = typeOracle.getType(SlottedTokenizer.class.getName());
        JClassType type = typeOracle.findType(typeName);

        if (type.isAssignableTo(placeBaseType)) {
            placeType = type;
        } else if (type.isAssignableTo(tokenizerBaseType)) {
            JClassType[] interfaces = type.getImplementedInterfaces();
            JClassType[] typeArgs = interfaces[0].isParameterized().getTypeArgs();
            placeType = typeArgs[0];
        }

        return placeType;
    }

    public SourceWriter getSourceWriter(JClassType classType, GeneratorContext context, TreeLogger logger) {

        String packageName = classType.getPackage().getName();
        String simpleName = classType.getSimpleSourceName() + NamePostfix;
        ClassSourceFileComposerFactory composer =
                new ClassSourceFileComposerFactory(packageName, simpleName);

        composer.addImplementedInterface(SlottedTokenizer.class.getCanonicalName() +
                "<" + classType.getQualifiedSourceName() + ">");
        composer.addImport(GWT.class.getCanonicalName());
        composer.addImport(PlaceTokenizer.class.getCanonicalName());
        composer.addImport(PlaceParameters.class.getCanonicalName());
        composer.addImport(TokenizerUtil.class.getCanonicalName());

        PrintWriter printWriter = context.tryCreate(logger, packageName,simpleName);

        if (printWriter == null) {
            return null;
        } else {
            SourceWriter sw = composer.createSourceWriter(context, printWriter);
            return sw;
        }

    }

    private void writeAccessors(SourceWriter sourceWriter, List<JField> fields, JClassType placeType) {
        for (JField field: fields) {
            writeAccessors(sourceWriter, field, placeType);
        }
    }

    private void writeAccessors(SourceWriter sourceWriter, JField field, JClassType placeType) {
        sourceWriter.println("private native void set" + field.getName() + "(" +
                placeType.getQualifiedSourceName() + " place, " +
                field.getType().getSimpleSourceName() + " value) /*-{");
        sourceWriter.println("    place.@" + placeType.getQualifiedSourceName() + "::" +
                field.getName() + " = value;");
        sourceWriter.println("}-*/;");

        sourceWriter.println("private native " + field.getType().getSimpleSourceName() + " get" +
                field.getName() + "(" + placeType.getQualifiedSourceName() + " place) /*-{");
        sourceWriter.println("    return place.@" + placeType.getQualifiedSourceName() + "::" +
                field.getName() + ";");
        sourceWriter.println("}-*/;");
        sourceWriter.println();
    }

    private void writeGlobalExtractor(SourceWriter sourceWriter, List<JField> fields, JClassType placeType) {
        sourceWriter.println("public void extractParameters(PlaceParameters intoPlaceParameters, " +
                placeType.getQualifiedSourceName() +" place) {");
        for (JField field: fields) {
            sourceWriter.println("    intoPlaceParameters.setParameter(\"" +
                    field.getName() + "\", get" + field.getName() + "(place));");
        }
        sourceWriter.println("}");
        sourceWriter.println();
    }

    private void writeGetToken(SourceWriter sourceWriter, List<JField> fields, JClassType placeType) {
        sourceWriter.println("public String getToken(" +
                placeType.getQualifiedSourceName() + " place) {");
        sourceWriter.println("    TokenizerUtil builder = TokenizerUtil.build();");
        for (JField field: fields) {
            sourceWriter.println("     builder.add(get" + field.getName() + "(place));");
        }
        sourceWriter.println("    return builder.tokenize();");
        sourceWriter.println("}");
        sourceWriter.println();
    }

    private void writeGetPlace(SourceWriter sourceWriter, List<JField> fields, JClassType placeType) {
        String placeString = placeType.getQualifiedSourceName();
        sourceWriter.println("public " + placeString + " getPlace(String token) {");
        sourceWriter.println("    " + placeString + " place = GWT.create(" + placeString + ".class);");
        sourceWriter.println("    TokenizerUtil extractor = TokenizerUtil.extract(token);");
        for (JField field: fields) {
        sourceWriter.println("    set" + field.getName() + "(place, extractor.get" +
                getGetMethod(field) + "());");
        }
        sourceWriter.println("    return place;");
        sourceWriter.println("}");
        sourceWriter.println();
    }

    private String getGetMethod(JField field) {
        String name = field.getType().getSimpleSourceName();
        if ("String".equals(name)) {
            return "";
        } else {
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }

    }
}