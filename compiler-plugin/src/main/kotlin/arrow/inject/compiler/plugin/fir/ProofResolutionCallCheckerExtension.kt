@file:OptIn(
  SymbolInternals::class,
  DfaInternals::class,
  PrivateForInline::class,
  SessionConfiguration::class,
  InternalDiagnosticFactoryMethod::class,
  InternalDiagnosticFactoryMethod::class,
  InternalDiagnosticFactoryMethod::class,
  InternalDiagnosticFactoryMethod::class,
)

package arrow.inject.compiler.plugin.fir

import arrow.inject.compiler.plugin.classpath.Classpath
import arrow.inject.compiler.plugin.fir.errors.FirMetaErrors
import arrow.inject.compiler.plugin.fir.errors.FirMetaErrors.UNRESOLVED_GIVEN_CALL_SITE
import arrow.inject.compiler.plugin.fir.utils.FirUtils
import arrow.inject.compiler.plugin.fir.utils.hasMetaContextAnnotation
import arrow.inject.compiler.plugin.fir.utils.isContextAnnotation
import arrow.inject.compiler.plugin.fir.utils.metaContextAnnotations
import arrow.inject.compiler.plugin.ir.utils.toIrType
import arrow.inject.compiler.plugin.proof.Proof
import arrow.inject.compiler.plugin.proof.ProofCacheKey
import arrow.inject.compiler.plugin.proof.ProofResolution
import arrow.inject.compiler.plugin.proof.asProofCacheKey
import arrow.inject.compiler.plugin.proof.putProofIntoCache
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.InternalDiagnosticFactoryMethod
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.PrivateForInline
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmKotlinMangler
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.resolve.calls.Candidate
import org.jetbrains.kotlin.fir.resolve.dfa.DfaInternals
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.fqName
import org.jetbrains.kotlin.fir.resolve.inference.ConeConstraintSystemUtilContext.unCapture
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirProviderImpl
import org.jetbrains.kotlin.fir.resolvedSymbol
import org.jetbrains.kotlin.fir.signaturer.FirBasedSignatureComposer
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.checker.SimpleClassicTypeSystemContext.asTypeArgument
import org.jetbrains.kotlin.types.checker.SimpleClassicTypeSystemContext.getType
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance

class ProofResolutionCallCheckerExtension(
  session: FirSession,
) : FirAdditionalCheckersExtension(session), FirUtils {

  private val allProofs: List<Proof> by lazy { collectLocalProofs() + collectRemoteProofs() }

  private val firBasedSignatureComposer: FirBasedSignatureComposer by lazy {
    FirBasedSignatureComposer(FirJvmKotlinMangler(session))
  }

  override val expressionCheckers: ExpressionCheckers =
    object : ExpressionCheckers() {
      override val callCheckers: Set<FirCallChecker> =
        setOf(
          object : FirCallChecker() {
            override fun check(
              expression: FirCall,
              context: CheckerContext,
              reporter: DiagnosticReporter
            ) {
              reportUnresolvedGivenCallSite(expression, context, reporter)
            }
          }
        )
    }

  override val counter: AtomicInteger = AtomicInteger(0)

  private fun reportUnresolvedGivenCallSite(
    expression: FirCall,
    context: CheckerContext,
    reporter: DiagnosticReporter
  ): Unit =
    unresolvedGivenCallSite(expression).let {
      resolvedParameters: Map<ProofResolution?, FirValueParameter> ->
      resolvedParameters.forEach { (proofResolution, valueParameter) ->
        val source = expression.source
        val proof = proofResolution?.proof
        if (proofResolution?.isAmbiguous == true && source != null && proof != null) {
          reporter.report(
            FirMetaErrors.AMBIGUOUS_PROOF_FOR_SUPERTYPE.on(
              source,
              proofResolution.targetType,
              proofResolution.proof,
              proofResolution.ambiguousProofs,
              AbstractSourceElementPositioningStrategy.DEFAULT,
            ),
            context
          )
        }

        val valueParameterSource: KtSourceElement? = valueParameter.source

        if (proofResolution?.proof == null && valueParameterSource != null) {
          reportMissingInductiveDependencies(expression, valueParameter, context, reporter)
          reporter.report(
            UNRESOLVED_GIVEN_CALL_SITE.on(
              valueParameterSource,
              expression,
              valueParameter.returnTypeRef.coneType,
              AbstractSourceElementPositioningStrategy.DEFAULT,
            ),
            context,
          )
        }
      }
    }

  private fun unresolvedGivenCallSite(call: FirCall): Map<ProofResolution?, FirValueParameter> {
    val originalFunction: FirFunction? =
      ((call as? FirResolvable)?.calleeReference?.resolvedSymbol as? FirFunctionSymbol<*>)?.fir

    return if (originalFunction?.isCompileTimeAnnotated == true) {
      val unresolvedValueParameters: List<FirValueParameter> =
        originalFunction.valueParameters.filter {
          val defaultValue: FirFunctionCall? = (it.defaultValue as? FirFunctionCall)
          defaultValue?.calleeReference?.resolvedSymbol == resolve.symbol
        }
      resolvedValueParametersMap(unresolvedValueParameters)
    } else {
      emptyMap()
    }
  }

  private fun resolvedValueParametersMap(unresolvedValueParameters: List<FirValueParameter>) =
    unresolvedValueParameters
      .mapNotNull { valueParameter: FirValueParameter ->
        val contextFqName: FqName? =
          valueParameter
            .annotations
            .firstOrNull { it.isContextAnnotation(session) }
            ?.fqName(session)

        val defaultValue = valueParameter.defaultValue

        if (contextFqName != null && defaultValue is FirQualifiedAccessExpression) {
          val proofResolution = resolveProof(contextFqName, valueParameter.returnTypeRef.coneType)
          if (proofResolution.proof == null) null to valueParameter
          else proofResolution to valueParameter
        } else {
          null
        }
      }
      .toMap()

  private fun resolveProof(contextFqName: FqName, type: ConeKotlinType): ProofResolution =
    proofCandidate(candidates = candidates(contextFqName, type), type = type).apply {
      putProofIntoCache(type.asProofCacheKey(contextFqName), this)
      // TODO: IMPORTANT CHECK TYPE IN PROOF_CACHE_KEY
    }

  private fun candidates(contextFqName: FqName, type: ConeKotlinType): Set<Candidate> =
    proofResolutionStageRunner.run {
      allProofs.filter { contextFqName in it.contexts(session) }.matchingCandidates(type)
    }

  private fun proofCandidate(candidates: Set<Candidate>, type: ConeKotlinType): ProofResolution {
    val candidate: Candidate = candidates.first(/*TODO() can be null?*/ )
    return ProofResolution(
      proof = Proof.Implication(candidate.symbol.fir.idSignature, candidate.symbol.fir),
      targetType = type,
      ambiguousProofs =
        (candidates - candidate).map { ambiguousCandidate ->
          Proof.Implication(
            ambiguousCandidate.symbol.fir.idSignature,
            ambiguousCandidate.symbol.fir
          )
        }
    )
  }

  private val classpath = Classpath(session)

  private fun collectLocalProofs(): List<Proof> =
    session.firstIsInstance<FirProviderImpl>().getAllFirFiles().flatMap { firFile ->
      val localProofs: MutableList<Proof> = mutableListOf()
      firFile.acceptChildren(
        visitor =
          object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
              val declaration = element as? FirDeclaration
              if (declaration != null && session.hasMetaContextAnnotation(declaration)) {
                localProofs.add(Proof.Implication(declaration.idSignature, declaration))
              }
            }
          }
      )
      localProofs
    }

  private fun collectRemoteProofs(): List<Proof> =
    classpath.firClasspathProviderResult.flatMap { result ->
      (result.functions + result.classes + result.properties + result.classProperties).map {
        Proof.Implication(it.fir.idSignature, it.fir)
      }
    }

  private val proofResolutionStageRunner: ProofResolutionStageRunner by lazy {
    ProofResolutionStageRunner(session)
  }

  private fun reportMissingInductiveDependencies(
    expression: FirCall,
    valueParameter: FirValueParameter,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
    if (session.hasMetaContextAnnotation(valueParameter)) {
      val classLikeDeclaration: FirClassLikeDeclaration? =
        valueParameter.returnTypeRef.firClassLike(session)

      if (classLikeDeclaration != null && classLikeDeclaration is FirClass) {
        classLikeDeclaration
          .constructors(session)
          .firstOrNull { it.isPrimary }
          ?.valueParameterSymbols
          ?.forEach { valueParameterSymbol ->
            val contextAnnotationFqName =
              session
                .metaContextAnnotations(valueParameterSymbol.fir)
                .firstOrNull()
                ?.fqName(session)

            val defaultValue =
              (valueParameterSymbol.fir.defaultValue as? FirQualifiedAccessExpression)

            val parameterResolveProof =
              if (contextAnnotationFqName != null && defaultValue != null) {
                resolveProof(
                  contextAnnotationFqName,
                  valueParameterSymbol.resolvedReturnType,
                )
              } else {
                null
              }
            val valueParameterSource: KtSourceElement? = valueParameter.source
            if (parameterResolveProof?.proof == null && valueParameterSource != null)
              reporter.report(
                UNRESOLVED_GIVEN_CALL_SITE.on(
                  valueParameterSource,
                  expression,
                  valueParameter.returnTypeRef.coneType,
                  AbstractSourceElementPositioningStrategy.DEFAULT,
                ),
                context,
              )
          }
      }
    }
  }

  val FirDeclaration.idSignature: IdSignature
    get() = checkNotNull(firBasedSignatureComposer.composeSignature(this))
}
