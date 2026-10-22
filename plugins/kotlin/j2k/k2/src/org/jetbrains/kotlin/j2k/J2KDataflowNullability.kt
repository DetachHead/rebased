// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.j2k

import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInspection.dataFlow.DfaNullability
import com.intellij.codeInspection.dataFlow.DfaPsiUtil
import com.intellij.codeInspection.dataFlow.DfaUtil
import com.intellij.codeInspection.dataFlow.NullabilityProblemKind
import com.intellij.codeInspection.dataFlow.NullabilityProblemKind.NullabilityProblem
import com.intellij.codeInspection.dataFlow.NullabilityUtil
import com.intellij.codeInspection.dataFlow.StandardDataFlowRunner
import com.intellij.codeInspection.dataFlow.inference.JavaSourceInference
import com.intellij.codeInspection.dataFlow.interpreter.RunnerResult
import com.intellij.codeInspection.dataFlow.interpreter.StandardDataFlowInterpreter
import com.intellij.codeInspection.dataFlow.java.JavaDfaListener
import com.intellij.codeInspection.dataFlow.jvm.descriptors.PlainDescriptor
import com.intellij.codeInspection.dataFlow.lang.DfaListener
import com.intellij.codeInspection.dataFlow.lang.UnsatisfiedConditionProblem
import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow
import com.intellij.codeInspection.dataFlow.lang.ir.DfaInstructionState
import com.intellij.codeInspection.dataFlow.lang.ir.FlushFieldsInstruction
import com.intellij.codeInspection.dataFlow.lang.ir.ReturnInstruction
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.value.DfaValue
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiArrayAccessExpression
import com.intellij.psi.PsiArrayInitializerExpression
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.impl.source.PsiExtensibleClass
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.JavaPsiConstructorUtil
import com.intellij.util.ThreeState
import com.siyeh.ig.psiutils.ExpressionUtils
import com.siyeh.ig.psiutils.MethodCallUtils
import org.jetbrains.annotations.ApiStatus
import com.intellij.codeInsight.Nullability as JavaNullability

@ApiStatus.Internal
class J2KDataflowNullability(private val file: PsiFile) {
    private enum class Flow { NOT_NULL, UNKNOWN, NULLABLE }

    private class Slot(val type: PsiType, val spec: Nullability?, val wildcard: PsiWildcardType? = null) {
        var flow: Flow? = null
        var demand = false
        var result = Nullability.Default
        val targets = LinkedHashSet<Slot>()
        val sources = LinkedHashSet<Slot>()
        var elements: List<Slot> = emptyList()

        val value: Flow?
            get() = when (spec) {
                Nullability.NotNull -> Flow.NOT_NULL
                Nullability.Nullable -> Flow.NULLABLE
                else -> flow
            }

        fun join(other: Flow): Boolean {
            val joined = flow?.let { maxOf(it, other) } ?: other
            if (joined == flow) return false
            flow = joined
            return true
        }

        fun decide(): Nullability = spec ?: when {
            flow == Flow.NULLABLE -> Nullability.Nullable
            demand || flow == Flow.NOT_NULL -> Nullability.NotNull
            else -> Nullability.Default
        }
    }

    private val slots = LinkedHashMap<PsiModifierListOwner, Slot>()
    private val librarySlots = HashMap<PsiMethod, Slot>()
    private val callSlots = HashMap<PsiMethodCallExpression, Slot>()
    private val elementSlots = ArrayList<Slot>()
    private val generated = LinkedHashSet<PsiModifierListOwner>()
    private val loopVariables = HashSet<PsiModifierListOwner>()
    private val methods = ArrayList<PsiMethod>()
    private val sinks = HashMap<PsiExpression, Slot>()
    private val visited = HashSet<PsiExpression>()
    private val originals = OriginalJavaSemanticResolver()
    private val originalFile by lazy { originals.originalElementOrSelf(file) }

    val decisions: Map<PsiModifierListOwner, Nullability> by lazy {
        collectSlots()
        collectEvidence()
        analyzeBodies()
        for ((expression, slot) in sinks) {
            if (expression !in visited) addStaticEvidence(expression, slot)
        }
        solve()
        resolve()
        slots.filterKeys { it !in generated && it !in loopVariables }.mapValues { it.value.result }
    }

    fun applyTo(inferrer: J2KNullityInferrer) {
        if (decisions.isEmpty()) return
        for (owner in decisions.keys) {
            val slot = slots.getValue(owner)
            val element = (slot.type as? PsiClassReferenceType)?.reference
            inferrer.nullableTypes.remove(slot.type)
            inferrer.notNullTypes.remove(slot.type)
            if (element != null) {
                inferrer.nullableElements.remove(element)
                inferrer.notNullElements.remove(element)
            }
            when (slot.result) {
                Nullability.Nullable -> {
                    inferrer.nullableTypes.add(slot.type)
                    if (element != null) inferrer.nullableElements.add(element)
                }

                Nullability.NotNull -> {
                    inferrer.notNullTypes.add(slot.type)
                    if (element != null) inferrer.notNullElements.add(element)
                }

                Nullability.Default -> {}
            }
        }
    }

    private fun collectSlots() {
        file.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitClass(aClass: PsiClass) {
                super.visitClass(aClass)
                if (aClass.isRecord) return
                val written = (aClass as? PsiExtensibleClass)?.ownMethods ?: return
                for (method in aClass.methods) {
                    if (method in written || method.body == null) continue
                    generated += method
                    generated += method.parameterList.parameters
                    methods += method
                    val returnType = method.returnType
                    if (!method.isConstructor && returnType != null) addSlot(method, returnType)
                    for (parameter in method.parameterList.parameters) {
                        if (!parameter.isVarArgs) addSlot(parameter, parameter.type)
                    }
                }
            }

            override fun visitField(field: PsiField) {
                super.visitField(field)
                if (field !is PsiEnumConstant) addSlot(field, field.type)
            }

            override fun visitMethod(method: PsiMethod) {
                super.visitMethod(method)
                methods += method
                val returnType = method.returnType
                if (!method.isConstructor && returnType != null && method.containingClass?.isAnnotationType != true) {
                    addSlot(method, returnType)
                }
            }

            override fun visitParameter(parameter: PsiParameter) {
                super.visitParameter(parameter)
                when (parameter.declarationScope) {
                    is PsiMethod -> if (!parameter.isVarArgs) addSlot(parameter, parameter.type)
                    is PsiForeachStatement -> {
                        addSlot(parameter, parameter.type)
                        loopVariables += parameter
                    }
                }
            }

            override fun visitLocalVariable(variable: PsiLocalVariable) {
                super.visitLocalVariable(variable)
                addSlot(variable, variable.type)
            }
        })
    }

    private fun addSlot(owner: PsiModifierListOwner, type: PsiType) {
        if (TypeConversionUtil.isPrimitiveAndNotNull(type) || PsiUtil.resolveClassInClassTypeOnly(type) is PsiTypeParameter) return
        if (PsiTreeUtil.getParentOfType(owner, PsiClass::class.java)?.isRecord == true || isJpaToManyDeclaration(owner)) return
        if (PsiTypesUtil.classNameEquals(type, CommonClassNames.JAVA_UTIL_OPTIONAL)) return
        val extensionNullability = when (owner) {
            is PsiParameter -> J2KNullabilityInferenceExtension.getNullability(owner)
            is PsiMethod -> J2KNullabilityInferenceExtension.getNullability(owner)
            is PsiField -> J2KNullabilityInferenceExtension.getNullability(owner)
            else -> null
        }?.takeIf { it != Nullability.Default }
        val info = NullableNotNullManager.getInstance(file.project).findEffectiveNullabilityInfo(owner)
        val slot = Slot(type, extensionNullability ?: info?.takeUnless { it.isInferred }?.nullability?.toJ2K())
        if (info != null && info.isInferred && owner !is PsiParameter) {
            when (info.nullability) {
                JavaNullability.NOT_NULL -> slot.join(Flow.NOT_NULL)
                JavaNullability.NULLABLE -> slot.join(Flow.NULLABLE)
                else -> {}
            }
        }
        slot.elements = createElements(type)
        slots[owner] = slot
    }

    private fun createElements(type: PsiType, receiver: Slot? = null): List<Slot> {
        val arguments = when (type) {
            is PsiArrayType -> listOf(type.componentType)
            is PsiClassType -> type.parameters.asList()
            else -> emptyList()
        }
        return arguments.map { argument ->
            val wildcard = argument as? PsiWildcardType
            val elementType = wildcard?.extendsBound ?: argument
            if (receiver != null && wildcard == null) receiverElement(receiver, elementType)?.let { return@map it }
            val spec = if (TypeConversionUtil.isPrimitiveAndNotNull(elementType)) Nullability.NotNull else elementType.nullability.nullability().toJ2K()
            Slot(elementType, spec, wildcard).also {
                it.elements = createElements(elementType, receiver)
                elementSlots += it
            }
        }
    }

    private fun linkElements(source: Slot, target: Slot) {
        if (source === target) return
        for ((sourceElement, targetElement) in source.elements.zip(target.elements)) {
            val wildcard = targetElement.wildcard
            if (wildcard?.isSuper != true) edge(sourceElement, targetElement)
            if (wildcard == null || wildcard.isSuper) edge(targetElement, sourceElement)
            linkElements(sourceElement, targetElement)
        }
    }

    private fun JavaNullability.toJ2K(): Nullability? = when (this) {
        JavaNullability.NOT_NULL -> Nullability.NotNull
        JavaNullability.NULLABLE -> Nullability.Nullable
        else -> null
    }

    private fun collectEvidence() {
        val visitor = object : JavaRecursiveElementWalkingVisitor() {
            override fun visitVariable(variable: PsiVariable) {
                super.visitVariable(variable)
                val slot = slots[variable] ?: return
                variable.initializer?.let { addSink(it, slot) }
            }

            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)
                val slot = expressionSlot(expression.lExpression) ?: return
                if (expression.operationTokenType == JavaTokenType.EQ) {
                    expression.rExpression?.let { addSink(it, slot) }
                } else {
                    slot.join(Flow.NOT_NULL)
                    if (PsiPrimitiveType.getUnboxedType(expression.lExpression.type) != null) slot.demand = true
                }
            }

            override fun visitCallExpression(callExpression: PsiCallExpression) {
                super.visitCallExpression(callExpression)
                addArgumentSinks(callExpression)
                if (callExpression is PsiMethodCallExpression) addReceiverElementSinks(callExpression)
            }

            override fun visitForeachStatement(statement: PsiForeachStatement) {
                super.visitForeachStatement(statement)
                val variable = slots[statement.iterationParameter] ?: return
                val iterated = expressionSlot(statement.iteratedValue) ?: return
                val element = if (iterated.type is PsiArrayType) iterated.elements.firstOrNull() else receiverElement(iterated, iterableElementType)
                if (element != null) {
                    edge(element, variable)
                    linkElements(element, variable)
                }
            }

            override fun visitEnumConstant(enumConstant: PsiEnumConstant) {
                super.visitEnumConstant(enumConstant)
                addArgumentSinks(enumConstant)
            }

            override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                super.visitMethodReferenceExpression(expression)
                val method = expression.resolve() as? PsiMethod ?: return
                for (parameter in method.parameterList.parameters) slots[parameter]?.join(Flow.UNKNOWN)
            }

            override fun visitBinaryExpression(expression: PsiBinaryExpression) {
                super.visitBinaryExpression(expression)
                addNullComparisonEvidence(expression)
            }
        }
        file.accept(visitor)
        for (owner in generated) (owner as? PsiMethod)?.body?.accept(visitor)

        for (method in methods) {
            val searchesAllUsers = searchesAllUsers(method)
            slots[method]?.let { slot ->
                if (method.body == null && !searchesAllUsers) slot.join(Flow.UNKNOWN)
                for (statement in PsiUtil.findReturnStatements(method)) statement.returnValue?.let { addSink(it, slot) }
            }
            addParameterEvidence(method)
            addOverrideEvidence(method)
            if (searchesAllUsers) addProjectEvidence(method)
        }
        for ((owner, slot) in slots) {
            if (owner is PsiField && searchesAllUsers(owner)) addProjectAssignmentEvidence(owner, slot)
        }
    }

    private fun searchesAllUsers(member: PsiMember): Boolean = member.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)

    private fun addSink(expression: PsiExpression, slot: Slot) {
        when (val stripped = PsiUtil.skipParenthesizedExprDown(expression) ?: return) {
            is PsiConditionalExpression -> {
                stripped.thenExpression?.let { addSink(it, slot) }
                stripped.elseExpression?.let { addSink(it, slot) }
            }

            is PsiTypeCastExpression -> stripped.operand?.let { addSink(it, slot) }
            else -> {
                sinks[stripped] = slot
                expressionSlot(stripped)?.let { linkElements(it, slot) }
                val initializer = (stripped as? PsiNewExpression)?.arrayInitializer ?: stripped as? PsiArrayInitializerExpression
                val element = slot.elements.firstOrNull()
                if (initializer != null && element != null) {
                    for (initializerElement in initializer.initializers) addSink(initializerElement, element)
                }
                if (stripped is PsiNewExpression && initializer == null) addNewArrayEvidence(stripped, slot)
            }
        }
    }

    private fun addNewArrayEvidence(expression: PsiNewExpression, slot: Slot) {
        val dimensions = expression.arrayDimensions
        var element = slot.elements.firstOrNull()
        for (index in dimensions.indices) {
            val current = element ?: return
            current.join(if (index == dimensions.lastIndex) Flow.NULLABLE else Flow.NOT_NULL)
            element = current.elements.firstOrNull()
        }
    }

    private fun addReceiverElementSinks(call: PsiMethodCallExpression) {
        val receiver = expressionSlot(call.methodExpression.qualifierExpression) ?: return
        if (receiver.elements.isEmpty()) return
        val method = call.resolveMethod() ?: return
        val arguments = call.argumentList.expressions
        for ((index, parameter) in method.parameterList.parameters.withIndex()) {
            if (parameter.isVarArgs) break
            val argument = arguments.getOrNull(index) ?: break
            if (PsiUtil.skipParenthesizedExprDown(argument) in sinks) continue
            receiverElement(receiver, parameter.type)?.let { addSink(argument, it) }
        }
    }

    private fun receiverElement(receiver: Slot, type: PsiType?): Slot? {
        val typeParameter = PsiUtil.resolveClassInClassTypeOnly(type) as? PsiTypeParameter ?: return null
        val owner = typeParameter.owner as? PsiClass ?: return null
        val receiverClass = PsiUtil.resolveClassInClassTypeOnly(receiver.type) ?: return null
        val mapped = if (owner == receiverClass) {
            typeParameter
        } else {
            if (!receiverClass.isInheritor(owner, true)) return null
            val substitutor = TypeConversionUtil.getSuperClassSubstitutor(owner, receiverClass, PsiSubstitutor.EMPTY)
            PsiUtil.resolveClassInClassTypeOnly(substitutor.substitute(typeParameter)) as? PsiTypeParameter ?: return null
        }
        return receiver.elements.getOrNull(receiverClass.typeParameters.indexOf(mapped))
    }

    private val iterableElementType: PsiType? by lazy {
        val iterable = JavaPsiFacade.getInstance(file.project).findClass(CommonClassNames.JAVA_LANG_ITERABLE, file.resolveScope)
        iterable?.typeParameters?.firstOrNull()?.let { JavaPsiFacade.getElementFactory(file.project).createType(it) }
    }

    private fun addArgumentSinks(call: PsiCall) {
        val arguments = call.argumentList?.expressions ?: return
        val isVarArgCall = MethodCallUtils.isVarArgCall(call)
        for (argument in arguments) {
            val parameter = MethodCallUtils.getParameterForArgument(argument) ?: continue
            if (!parameter.isVarArgs) {
                slots[parameter]?.let { addSink(argument, it) }
            } else if (!isVarArgCall) {
                variableSlot(argument)?.demand = true
            }
        }
    }

    private fun addNullComparisonEvidence(expression: PsiBinaryExpression) {
        val operation = expression.operationTokenType
        if (operation != JavaTokenType.EQEQ && operation != JavaTokenType.NE) return
        val left = expression.lOperand
        val right = expression.rOperand ?: return
        val operand = when {
            ExpressionUtils.isNullLiteral(right) -> left
            ExpressionUtils.isNullLiteral(left) -> right
            else -> return
        }
        val reference = PsiUtil.skipParenthesizedExprDown(operand) as? PsiReferenceExpression ?: return
        val slot = variableSlot(reference) ?: return
        if (getExpressionDfaNullability(reference) == DfaNullability.NOT_NULL) return
        if (DfaPsiUtil.isAssertionEffectively(expression, operation == JavaTokenType.NE)) {
            slot.demand = true
        } else {
            slot.join(Flow.NULLABLE)
        }
    }

    private fun addParameterEvidence(method: PsiMethod) {
        val hasUnseenCallers = !method.hasModifierProperty(PsiModifier.PRIVATE) && !searchesAllUsers(method)
        for (parameter in method.parameterList.parameters) {
            val slot = slots[parameter] ?: continue
            if (hasUnseenCallers) slot.join(Flow.UNKNOWN)
            if (method.body != null && JavaSourceInference.inferNullability(parameter) == JavaNullability.NOT_NULL) slot.demand = true
        }
    }

    private fun addOverrideEvidence(method: PsiMethod) {
        val returnSlot = slots[method]
        for (superMethod in method.findSuperMethods()) {
            val superReturnSlot = slots[superMethod]
            if (returnSlot != null && superReturnSlot != null) {
                edge(returnSlot, superReturnSlot)
                linkElements(returnSlot, superReturnSlot)
            }
            for ((parameter, superParameter) in method.parameterList.parameters.zip(superMethod.parameterList.parameters)) {
                val parameterSlot = slots[parameter] ?: continue
                val superParameterSlot = slots[superParameter] ?: continue
                edge(parameterSlot, superParameterSlot)
                edge(superParameterSlot, parameterSlot)
                linkElements(parameterSlot, superParameterSlot)
                linkElements(superParameterSlot, parameterSlot)
            }
        }
    }

    private fun addProjectEvidence(method: PsiMethod) {
        val parameterSlots = method.parameterList.parameters.map { slots[it] }
        if (parameterSlots.any { it != null }) {
            for (element in referencesOutsideFile(method)) {
                val arguments = (element.parent as? PsiCall)?.argumentList?.expressions
                if (arguments == null && element !is PsiMethodReferenceExpression) continue
                for ((index, slot) in parameterSlots.withIndex()) {
                    if (slot == null) continue
                    val argument = arguments?.getOrNull(index)
                    if (argument != null) addStaticEvidence(argument, slot) else slot.join(Flow.UNKNOWN)
                }
            }
        }
        val returnSlot = slots[method] ?: return
        if (method.isConstructor || method.hasModifierProperty(PsiModifier.STATIC) || method.hasModifierProperty(PsiModifier.FINAL)) return
        for (overrider in OverridingMethodsSearch.search(originals.originalElementOrSelf(method)).findAll()) {
            if (overrider.containingFile == originalFile) continue
            val nullability = NullableNotNullManager.getNullability(overrider).takeIf { it != JavaNullability.UNKNOWN }
                ?: DfaUtil.inferMethodNullability(overrider)
            returnSlot.join(
                when (nullability) {
                    JavaNullability.NOT_NULL -> Flow.NOT_NULL
                    JavaNullability.NULLABLE -> Flow.NULLABLE
                    else -> Flow.UNKNOWN
                }
            )
        }
    }

    private fun addProjectAssignmentEvidence(field: PsiField, slot: Slot) {
        for (element in referencesOutsideFile(field)) {
            val assignment = element.parent as? PsiAssignmentExpression ?: continue
            if (assignment.lExpression != element) continue
            val value = assignment.rExpression
            if (assignment.operationTokenType == JavaTokenType.EQ && value != null) addStaticEvidence(value, slot) else slot.join(Flow.NOT_NULL)
        }
    }

    private fun referencesOutsideFile(member: PsiMember): List<PsiElement> {
        val original = originals.originalElementOrSelf(member)
        return ReferencesSearch.search(original, original.useScope).findAll().map { it.element }.filter { it.containingFile != originalFile }
    }

    private fun analyzeBodies() {
        file.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitClass(aClass: PsiClass) {
                if (isConstructed(aClass)) analyzeConstruction(aClass)
                super.visitClass(aClass)
            }

            override fun visitMethod(method: PsiMethod) {
                if (method.isConstructor && isConstructed(method.containingClass)) return
                method.body?.let(::analyze)
            }

            override fun visitClassInitializer(initializer: PsiClassInitializer) {
                if (isConstructed(initializer.containingClass)) return
                analyze(initializer.body)
            }
        })
    }

    private fun isConstructed(aClass: PsiClass?): Boolean =
        aClass != null && aClass !is PsiTypeParameter && !aClass.isInterface && !aClass.isRecord &&
            !PsiUtil.isLocalOrAnonymousClass(aClass)

    private fun analyzeConstruction(aClass: PsiClass) {
        val runner = ConstructionRunner()
        val classEvidence = BodyEvidence()
        runner.checkAtReturn = false
        runner.fieldsToCheck = aClass.fields.filter { it.hasModifierProperty(PsiModifier.STATIC) && it in slots }
        if (runner.analyzeBlockRecursively(aClass, listOf(runner.freshState()), classEvidence) != RunnerResult.OK) return
        classEvidence.commit()

        val instanceFields = aClass.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) && it in slots }
        val initializedStates = classEvidence.endOfInitializerStates.ifEmpty { listOf(runner.freshState()) }
        val constructors = aClass.constructors
        if (constructors.isEmpty()) {
            for (state in initializedStates) runner.check(state, instanceFields)
        }
        for (constructor in constructors) {
            val body = constructor.body ?: continue
            val chained = JavaPsiConstructorUtil.isChainedConstructorCall(JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(constructor))
            runner.checkAtReturn = true
            runner.fieldsToCheck = if (chained) emptyList() else instanceFields
            val states = if (chained) listOf(runner.freshState()) else initializedStates.map { it.createCopy() }
            val evidence = BodyEvidence()
            if (runner.analyzeBlockRecursively(body, states, evidence) == RunnerResult.OK) evidence.commit()
        }
        for (field in runner.nullAtEnd) slots[field]?.join(Flow.NULLABLE)
    }

    private inner class ConstructionRunner : StandardDataFlowRunner(file.project, ThreeState.UNSURE) {
        var checkAtReturn = false
        var fieldsToCheck: List<PsiField> = emptyList()
        val nullAtEnd = HashSet<PsiField>()

        fun freshState(): DfaMemoryState = createMemoryState()

        fun check(state: DfaMemoryState, fields: List<PsiField>) {
            for (field in fields) {
                val value = PlainDescriptor.createVariableValue(factory, field)
                if (DfaNullability.fromDfType(state.getDfType(value)) == DfaNullability.NULL) nullAtEnd += field
            }
        }

        override fun createInterpreter(listener: DfaListener, flow: ControlFlow): StandardDataFlowInterpreter {
            flow.keepVariables { it is PlainDescriptor && it.psiElement in fieldsToCheck }
            return object : StandardDataFlowInterpreter(flow, listener) {
                override fun acceptInstruction(instructionState: DfaInstructionState): Array<DfaInstructionState> {
                    val instruction = instructionState.instruction
                    val isEnd = if (checkAtReturn) instruction is ReturnInstruction else instruction is FlushFieldsInstruction
                    if (isEnd) check(instructionState.memoryState, fieldsToCheck)
                    return super.acceptInstruction(instructionState)
                }
            }
        }
    }

    private fun analyze(body: PsiCodeBlock) {
        val evidence = BodyEvidence()
        val result = StandardDataFlowRunner(file.project, ThreeState.UNSURE).analyzeMethodRecursively(body, evidence)
        if (result == RunnerResult.OK) evidence.commit()
    }

    private inner class BodyEvidence : JavaDfaListener {
        private val flows = ArrayList<Pair<Slot, Flow>>()
        private val edges = ArrayList<Pair<Slot, Slot>>()
        private val demands = ArrayList<Slot>()
        private val pushed = HashSet<PsiExpression>()
        val endOfInitializerStates = ArrayList<DfaMemoryState>()

        override fun beforeInstanceInitializerEnd(state: DfaMemoryState) {
            endOfInitializerStates += state.createCopy()
        }

        override fun beforeExpressionPush(value: DfaValue, expression: PsiExpression, state: DfaMemoryState) {
            val target = sinks[expression] ?: return
            pushed += expression
            if (state.getDfType(value) == DfType.FAIL) return
            val nullability = if (TypeConversionUtil.isPrimitiveAndNotNull(expression.type)) {
                DfaNullability.NOT_NULL
            } else {
                DfaNullability.fromDfType(state.getDfTypeIncludingDerived(value))
            }
            when (nullability) {
                DfaNullability.NULL, DfaNullability.NULLABLE -> flows += target to Flow.NULLABLE
                DfaNullability.NOT_NULL -> flows += target to Flow.NOT_NULL
                else -> when (NullabilityUtil.getExpressionNullability(expression, false)) {
                    JavaNullability.NOT_NULL -> flows += target to Flow.NOT_NULL
                    JavaNullability.NULLABLE -> flows += target to Flow.NULLABLE
                    else -> {
                        val source = sourceSlot(value, expression)
                        if (source != null) edges += source to target else flows += target to Flow.UNKNOWN
                    }
                }
            }
        }

        override fun onCondition(problem: UnsatisfiedConditionProblem, value: DfaValue, failed: ThreeState, state: DfaMemoryState) {
            if (problem !is NullabilityProblem<*> || problem.kind !in DEREFERENCE_KINDS) return
            val variable = (value as? DfaVariableValue)?.psiVariable as? PsiVariable ?: return
            val slot = slots[variable] ?: return
            val nullability = DfaNullability.fromDfType(state.getDfType(value))
            if (nullability == DfaNullability.UNKNOWN || nullability == DfaNullability.FLUSHED) demands += slot
        }

        fun commit() {
            for ((slot, flow) in flows) slot.join(flow)
            for ((source, target) in edges) edge(source, target)
            for (slot in demands) slot.demand = true
            visited += pushed
        }
    }

    private fun sourceSlot(value: DfaValue, expression: PsiExpression): Slot? {
        val variable = (value as? DfaVariableValue)?.psiVariable as? PsiModifierListOwner
        if (variable != null) slots[variable]?.let { return it }
        return expressionSlot(expression)
    }

    private fun expressionSlot(expression: PsiExpression?): Slot? = when (val stripped = PsiUtil.skipParenthesizedExprDown(expression)) {
        is PsiReferenceExpression -> (stripped.resolve() as? PsiVariable)?.let { slots[it] }
        is PsiMethodCallExpression -> calleeSlot(stripped)
        is PsiArrayAccessExpression -> expressionSlot(stripped.arrayExpression)?.elements?.firstOrNull()
        else -> null
    }

    private fun variableSlot(expression: PsiExpression?): Slot? {
        val reference = PsiUtil.skipParenthesizedExprDown(expression) as? PsiReferenceExpression ?: return null
        val variable = reference.resolve() as? PsiVariable ?: return null
        return slots[variable]
    }

    private fun calleeSlot(call: PsiMethodCallExpression): Slot? {
        val method = call.resolveMethod() ?: return null
        slots[method]?.let { return it }
        callSlots[call]?.let { return it }
        val type = method.returnType ?: return null
        val receiver = expressionSlot(call.methodExpression.qualifierExpression)
        if (receiver != null) {
            receiverElement(receiver, type)?.let { return it }
            val elements = if (receiver.elements.isEmpty()) emptyList() else createElements(type, receiver)
            if (elements.isNotEmpty()) {
                val nullability = effectiveNullability(method)
                return Slot(type, nullability).also {
                    if (nullability == null) it.join(Flow.UNKNOWN)
                    it.elements = elements
                    callSlots[call] = it
                }
            }
        }
        librarySlots[method]?.let { return it }
        if (method.containingFile == file) return null
        val nullability = effectiveNullability(method) ?: return null
        return Slot(type, nullability).also {
            it.elements = createElements(type)
            librarySlots[method] = it
        }
    }

    private fun effectiveNullability(method: PsiMethod): Nullability? =
        NullableNotNullManager.getInstance(file.project).findEffectiveNullabilityInfo(method)?.nullability?.toJ2K()

    private fun addStaticEvidence(expression: PsiExpression, slot: Slot) {
        when (NullabilityUtil.getExpressionNullability(expression, true)) {
            JavaNullability.NOT_NULL -> slot.join(Flow.NOT_NULL)
            JavaNullability.NULLABLE -> slot.join(Flow.NULLABLE)
            else -> {
                val source = expressionSlot(expression)
                if (source != null) {
                    edge(source, slot)
                    linkElements(source, slot)
                } else {
                    slot.join(Flow.UNKNOWN)
                }
            }
        }
    }

    private fun edge(source: Slot, target: Slot) {
        if (source === target) return
        source.targets += target
        target.sources += source
    }

    private fun allSlots(): List<Slot> = buildList {
        addAll(slots.values)
        addAll(librarySlots.values)
        addAll(callSlots.values)
        addAll(elementSlots)
    }

    private fun solve() {
        val queue = ArrayDeque(allSlots())
        while (queue.isNotEmpty()) {
            val slot = queue.removeFirst()
            val value = slot.value ?: continue
            for (target in slot.targets) {
                if (target.spec == null && target.join(value)) queue += target
            }
        }
    }

    private fun resolve() {
        val all = allSlots()
        for (slot in all) slot.result = slot.decide()
        val queue = ArrayDeque(all.filter { it.result == Nullability.NotNull })
        while (queue.isNotEmpty()) {
            for (source in queue.removeFirst().sources) {
                if (source.spec != null || source.demand || source.flow == Flow.NULLABLE) continue
                source.demand = true
                source.result = source.decide()
                queue += source
            }
        }
    }

    companion object {
        private val DEREFERENCE_KINDS: Set<NullabilityProblemKind<*>> = setOf(
            NullabilityProblemKind.callNPE,
            NullabilityProblemKind.callMethodRefNPE,
            NullabilityProblemKind.innerClassNPE,
            NullabilityProblemKind.templateNPE,
            NullabilityProblemKind.fieldAccessNPE,
            NullabilityProblemKind.arrayAccessNPE,
            NullabilityProblemKind.unboxingNullable,
            NullabilityProblemKind.unboxingMethodRefParameter,
            NullabilityProblemKind.passingToNotNullParameter,
            NullabilityProblemKind.passingToNotNullMethodRefParameter,
            NullabilityProblemKind.assigningToNotNull,
            NullabilityProblemKind.storingToNotNullArray,
        )
    }
}
