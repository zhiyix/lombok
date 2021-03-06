/*
 * Copyright (C) 2014-2018 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import static lombok.core.handlers.HandlerUtil.handleExperimentalFlagUsage;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.util.ArrayList;
import java.util.List;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.eclipse.Eclipse;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.experimental.FieldNameConstants;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Clinit;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.mangosdk.spi.ProviderFor;

@ProviderFor(EclipseAnnotationHandler.class)
public class HandleFieldNameConstants extends EclipseAnnotationHandler<FieldNameConstants> {
	public void generateFieldNameConstantsForType(EclipseNode typeNode, EclipseNode errorNode, AccessLevel level, boolean asEnum, String innerTypeName, boolean onlyExplicit) {
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers & (ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation)) != 0;
		
		if (typeDecl == null || notAClass) {
			errorNode.addError("@FieldNameConstants is only supported on a class or an enum.");
			return;
		}
		
		List<EclipseNode> qualified = new ArrayList<EclipseNode>();
		
		for (EclipseNode field : typeNode.down()) {
			if (fieldQualifiesForFieldNameConstantsGeneration(field, onlyExplicit)) qualified.add(field);
		}
		
		if (qualified.isEmpty()) {
			errorNode.addWarning("No fields qualify for @FieldNameConstants, therefore this annotation does nothing");
		} else {
			createInnerTypeFieldNameConstants(typeNode, errorNode.get(), level, qualified, asEnum, innerTypeName);
		}
	}
	
	private boolean fieldQualifiesForFieldNameConstantsGeneration(EclipseNode field, boolean onlyExplicit) {
		if (field.getKind() != Kind.FIELD) return false;
		if (hasAnnotation(FieldNameConstants.Exclude.class, field)) return false;
		if (hasAnnotation(FieldNameConstants.Include.class, field)) return true;
		if (onlyExplicit) return false;
		
		FieldDeclaration fieldDecl = (FieldDeclaration) field.get();
		return filterField(fieldDecl);
	}
	
	public void handle(AnnotationValues<FieldNameConstants> annotation, Annotation ast, EclipseNode annotationNode) {
		handleExperimentalFlagUsage(annotationNode, ConfigurationKeys.FIELD_NAME_CONSTANTS_FLAG_USAGE, "@FieldNameConstants");
		
		EclipseNode node = annotationNode.up();
		FieldNameConstants annotationInstance = annotation.getInstance();
		AccessLevel level = annotationInstance.level();
		boolean asEnum = annotationInstance.asEnum();
		boolean usingLombokv1_18_2 = annotation.isExplicit("prefix") || annotation.isExplicit("suffix") || node.getKind() == Kind.FIELD;
		
		if (usingLombokv1_18_2) {
			annotationNode.addError("@FieldNameConstants has been redesigned in lombok v1.18.4; please upgrade your project dependency on lombok. See https://projectlombok.org/features/experimental/FieldNameConstants for more information.");
			return;
		}
		
		if (level == AccessLevel.NONE) {
			annotationNode.addWarning("AccessLevel.NONE is not compatible with @FieldNameConstants. If you don't want the inner type, simply remove FieldNameConstants.");
			return;
		}
		
		String innerTypeName = annotationInstance.innerTypeName();
		if (innerTypeName.isEmpty()) innerTypeName = annotationNode.getAst().readConfiguration(ConfigurationKeys.FIELD_NAME_CONSTANTS_INNER_TYPE_NAME);
		if (innerTypeName == null || innerTypeName.isEmpty()) innerTypeName = "Fields";
		
		generateFieldNameConstantsForType(node, annotationNode, level, asEnum, innerTypeName, annotationInstance.onlyExplicitlyIncluded());
	}
	
	private void createInnerTypeFieldNameConstants(EclipseNode typeNode, ASTNode source, AccessLevel level, List<EclipseNode> fields, boolean asEnum, String innerTypeName) {
		if (fields.isEmpty()) return;
		
		TypeDeclaration parent = (TypeDeclaration) typeNode.get();
		TypeDeclaration innerType = new TypeDeclaration(parent.compilationResult);
		innerType.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		innerType.modifiers = toEclipseModifier(level) | (asEnum ? ClassFileConstants.AccEnum : ClassFileConstants.AccStatic | ClassFileConstants.AccFinal);
		char[] name = innerTypeName.toCharArray();
		innerType.name = name;
		innerType.traverse(new SetGeneratedByVisitor(source), (ClassScope) null);
		EclipseNode innerNode = injectType(typeNode, innerType);
		
		ConstructorDeclaration constructor = new ConstructorDeclaration(parent.compilationResult);
		constructor.selector = name;
		constructor.declarationSourceStart = constructor.sourceStart = source.sourceStart;
		constructor.declarationSourceEnd = constructor.sourceEnd = source.sourceEnd;
		constructor.modifiers = ClassFileConstants.AccPrivate;
		ExplicitConstructorCall superCall = new ExplicitConstructorCall(0);
		superCall.sourceStart = source.sourceStart;
		superCall.sourceEnd = source.sourceEnd;
		superCall.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		constructor.constructorCall = superCall;
		if (!asEnum) constructor.statements = new Statement[0];
		
		injectMethod(innerNode, constructor);
		
		if (asEnum) injectMethod(innerNode, new Clinit(parent.compilationResult));
		
		for (EclipseNode fieldNode : fields) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] fName = field.name;
			int pS = source.sourceStart, pE = source.sourceEnd;
			long p = (long) pS << 32 | pE;
			FieldDeclaration fieldConstant = new FieldDeclaration(fName, pS, pE);
			fieldConstant.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
			fieldConstant.modifiers = asEnum ? 0 : ClassFileConstants.AccPublic | ClassFileConstants.AccStatic | ClassFileConstants.AccFinal;
			fieldConstant.type = asEnum ? null : new QualifiedTypeReference(TypeConstants.JAVA_LANG_STRING, new long[] {p, p, p});
			if (asEnum) {
				AllocationExpression ac = new AllocationExpression();
				ac.enumConstant = fieldConstant;
				ac.sourceStart = source.sourceStart;
				ac.sourceEnd = source.sourceEnd;
				fieldConstant.initialization = ac;
			} else {
				fieldConstant.initialization = new StringLiteral(field.name, pS, pE, 0);
			}
			injectField(innerNode, fieldConstant);
		}
	}
}
