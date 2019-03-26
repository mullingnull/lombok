package lombok.javac.handlers;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.PeepIn;
import org.mangosdk.spi.ProviderFor;

import java.util.Collection;

import static lombok.core.handlers.HandlerUtil.handleFlagUsage;
import static lombok.javac.handlers.JavacHandlerUtil.*;

/**
 * Handles the {@code PeepIn} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandlePeepIn extends JavacAnnotationHandler<PeepIn> {

    private static final String DEFAULT_PREFIX = "__";

    public void generateSFNForType(JavacNode typeNode, JavacNode errorNode, boolean checkForTypeLevelAnnotation, AccessLevel level, String customer) {
        if (checkForTypeLevelAnnotation) {
            if (hasAnnotation(PeepIn.class, typeNode)) {
                //The annotation will make it happen, so we can skip it.
                return;
            }
        }

        JCClassDecl typeDecl = null;
        if (typeNode.get() instanceof JCClassDecl) {
            typeDecl = (JCClassDecl) typeNode.get();
        }
        long modifiers = typeDecl == null ? 0 : typeDecl.mods.flags;
        boolean notAClass = (modifiers & (Flags.INTERFACE | Flags.ANNOTATION | Flags.ENUM)) != 0;

        if (typeDecl == null || notAClass) {
            errorNode.addError("@PeepIn is only supported on a class, or a field.");
            return;
        }

        for (JavacNode field : typeNode.down()) {
            if (fieldQualifiesForGetterGeneration(field)) {
                generateSFNForField(field, level, customer);
            }
        }
    }

    public static boolean fieldQualifiesForGetterGeneration(JavacNode field) {
        if (field.getKind() != Kind.FIELD) return false;
        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
        //Skip fields that start with $
        if (fieldDecl.name.toString().startsWith("$")) return false;
        //Skip static fields.
        if ((fieldDecl.mods.flags & Flags.STATIC) != 0) return false;
        //Skip final fields.
        if ((fieldDecl.mods.flags & Flags.FINAL) != 0) return false;
        return true;
    }

    public void generateSFNForField(JavacNode fieldNode, AccessLevel level, String customer) {
        if (hasAnnotation(PeepIn.class, fieldNode)) {
            //The annotation will make it happen, so we can skip it.
            return;
        }
        createSFNForField(fieldNode, fieldNode, level, customer);
    }

    @Override
    public void handle(AnnotationValues<PeepIn> annotation, JCAnnotation ast, JavacNode annotationNode) {

        handleFlagUsage(annotationNode, ConfigurationKeys.PEEP_IN_FLAG_USAGE, "@PeepIn");

        Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
        if (fields.size() > 1) {
            annotationNode.addError("@PeepIn is not supported for multi fields");
            return;
        }
        deleteAnnotationIfNeccessary(annotationNode, PeepIn.class);
        deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
        JavacNode node = annotationNode.up();
        PeepIn annotationInstance = annotation.getInstance();
        AccessLevel level = annotationInstance.level();
        String customer = annotationInstance.customer();
        if (customer != null && customer.trim().isEmpty()) {
            customer = null;
        }

        if (level == AccessLevel.NONE) {
            return;
        }

        if (node == null) {
            return;
        }

        switch ( node.getKind() ) {
            case FIELD:
                createSFNForField(node, annotationNode, level, markAsFinalName(customer));
                break;
            case TYPE:
                generateSFNForType(node, annotationNode, false, level, customer);
                break;
            default:
        }
    }

    public void createSFNForField(JavacNode fieldNode, JavacNode source, AccessLevel level, String customer) {
        if (fieldNode.getKind() != Kind.FIELD) {
            source.addError("@PeepIn is only supported on a class or a field.");
            return;
        }

        String staticFieldName = toStaticFieldName(fieldNode, customer);

        if (staticFieldName == null) {
            source.addWarning("Not generating PeepIn for this field: It does not fit your @Accessors prefix list.");
            return;
        }

        switch ( fieldExists(staticFieldName, fieldNode) ) {
            case EXISTS_BY_LOMBOK:
                return;
            case EXISTS_BY_USER:
                source.addWarning(String.format("Not generating %s(): already exists", staticFieldName));
                return;
            default:
            case NOT_EXISTS:
                //continue creating and injecting.
        }

        long access = toJavacModifier(level) | Flags.FINAL | Flags.STATIC;

        injectField(fieldNode.up(), createSFN(fieldNode, fieldNode.getTreeMaker(), access, staticFieldName));
    }

    private String toStaticFieldName(JavacNode fieldNode, String customer) {
        if (isFinalName(customer)) {
            return getFinalName(customer);
        }
        String camelName = fieldNode.getName();
        int length = camelName.length();
        StringBuilder underScoreStyle = new StringBuilder(length * 4);
        if (customer != null && !customer.isEmpty()) {
            underScoreStyle.append(customer);
        } else {
            underScoreStyle.append(DEFAULT_PREFIX);
        }
        for (int i = 0; i < length; i++) {
            int codePoint = camelName.codePointAt(i);
            if (Character.isDigit(codePoint)) {
                underScoreStyle.append('_').append((char) codePoint).append('_');
            } else if (Character.isUpperCase(codePoint) && ((i > 1 && Character.isLowerCase(camelName.charAt(i - 1))) || (i + 1 < length && Character.isLowerCase(camelName.charAt(i + 1))))) {
                //consider of 'hotelIDNumber'
                underScoreStyle.append('_').append((char) codePoint);
            } else if (Character.isJavaIdentifierPart(codePoint)) {
                underScoreStyle.append((char) Character.toUpperCase(codePoint));
            } else {
                return null;
            }
        }
        return underScoreStyle.toString();
    }

    public JCVariableDecl createSFN(JavacNode field, JavacTreeMaker treeMaker, long access, String staticFieldName) {
        return treeMaker.VarDef(treeMaker.Modifiers(access), field.toName(staticFieldName), genJavaLangTypeRef(field, "String"),
                treeMaker.Literal(field.getName()));
    }

    private String markAsFinalName(String name) {
        if (name == null) {
            return null;
        }
        return "8" + name;
    }

    private boolean isFinalName(String name) {
        return name != null && name.startsWith("8");
    }

    private String getFinalName(String name) {
        return name.replaceFirst("8", "");
    }
}
