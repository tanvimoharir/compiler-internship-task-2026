package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.tree.ParseTree

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    
    private var indentLevel = 0
    private val indent get() = "  ".repeat(indentLevel)
    
    // Track continuation parameter naming
    private var continuationDepth = 0
    
    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        val functions = program.functionDeclaration().map { visitFunctionDeclaration(it) }.joinToString("\n\n")
        
        return """
public class $className {
$functions
}
        """.trimIndent()
    }
    
    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val functionName = ctx.IDENTIFIER().text
        val returnType = mapType(ctx.type().text)
        val params = ctx.parameterList()?.parameter()?.map { param ->
            val paramName = param.IDENTIFIER().text
            val paramType = mapType(param.type().text)
            "$paramType $paramName"
        } ?: emptyList()
        
        // Add continuation parameter for non-main functions
        val signature = if (functionName == "main") {
            "public static void main(String[] args)"
        } else {
            val allParams = if (returnType == "Void") {
                params + "Continuation<Void> __continuation"
            } else {
                params + "Continuation<$returnType> __continuation"
            }
            "public static void $functionName(${allParams.joinToString(", ")})"
        }
        
        indentLevel = 1
        val body = visitBlock(ctx.block())
        indentLevel = 0
        
        return "$signature $body"
    }
    
    override fun visitBlock(ctx: MiniKotlinParser.BlockContext): String {
        val statements = ctx.statement()
        if (statements.isEmpty()) {
            return "{\n$indent}"
        }
        
        indentLevel++
        val compiledStatements = compileStatementSequence(statements.toList(), 0)
        indentLevel--
        
        return "{\n$compiledStatements\n$indent}"
    }
    
    private fun compileStatementSequence(statements: List<MiniKotlinParser.StatementContext>, index: Int): String {
        if (index >= statements.size) {
            return ""
        }
        
        val stmt = statements[index]
        val restStatements = statements.drop(index + 1)
        
        return when {
            stmt.variableDeclaration() != null -> {
                compileVariableDeclarationInSequence(stmt.variableDeclaration(), restStatements)
            }
            stmt.variableAssignment() != null -> {
                compileVariableAssignmentInSequence(stmt.variableAssignment(), restStatements)
            }
            stmt.returnStatement() != null -> {
                visitReturnStatement(stmt.returnStatement())
            }
            stmt.ifStatement() != null -> {
                compileIfStatementInSequence(stmt.ifStatement(), restStatements)
            }
            stmt.whileStatement() != null -> {
                compileWhileStatementInSequence(stmt.whileStatement(), restStatements)
            }
            stmt.expression() != null -> {
                compileExpressionStatementInSequence(stmt.expression(), restStatements)
            }
            else -> ""
        }
    }
    
    private fun compileVariableDeclarationInSequence(
        ctx: MiniKotlinParser.VariableDeclarationContext,
        restStatements: List<MiniKotlinParser.StatementContext>
    ): String {
        val varName = ctx.IDENTIFIER().text
        val varType = mapType(ctx.type().text)
        val expr = ctx.expression()
        
        if (containsFunctionCall(expr)) {
            return when (expr) {
                is MiniKotlinParser.FunctionCallExprContext -> {
                    val functionName = expr.IDENTIFIER().text
                    val args = expr.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
                    val contParamName = "arg${continuationDepth}"
                    continuationDepth++
                    
                    buildString {
                        append("$indent${mapFunctionName(functionName)}(${args.joinToString(", ")}, ($contParamName) -> {\n")
                        indentLevel++
                        append("$indent$varType $varName = $contParamName;\n")
                        
                        if (restStatements.isNotEmpty()) {
                            append(compileStatementSequence(restStatements, 0))
                        }
                        
                        indentLevel--
                        append("$indent});")
                    }.also { continuationDepth-- }
                }
                else -> {
                    val value = visitExpression(expr)
                    buildString {
                        append("$indent$varType $varName = $value;\n")
                        if (restStatements.isNotEmpty()) {
                            append(compileStatementSequence(restStatements, 0))
                        }
                    }
                }
            }
        } else {
            val value = visitExpression(expr)
            return buildString {
                append("$indent$varType $varName = $value;\n")
                if (restStatements.isNotEmpty()) {
                    append(compileStatementSequence(restStatements, 0))
                }
            }
        }
    }
    
    private fun compileVariableAssignmentInSequence(
        ctx: MiniKotlinParser.VariableAssignmentContext,
        restStatements: List<MiniKotlinParser.StatementContext>
    ): String {
        val varName = ctx.IDENTIFIER().text
        val expr = ctx.expression()
        
        if (containsFunctionCall(expr)) {
            return when (expr) {
                is MiniKotlinParser.FunctionCallExprContext -> {
                    val functionName = expr.IDENTIFIER().text
                    val args = expr.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
                    val contParamName = "arg${continuationDepth}"
                    continuationDepth++
                    
                    buildString {
                        append("$indent${mapFunctionName(functionName)}(${args.joinToString(", ")}, ($contParamName) -> {\n")
                        indentLevel++
                        append("$indent$varName = $contParamName;\n")
                        
                        if (restStatements.isNotEmpty()) {
                            append(compileStatementSequence(restStatements, 0))
                        }
                        
                        indentLevel--
                        append("$indent});")
                    }.also { continuationDepth-- }
                }
                else -> {
                    val value = visitExpression(expr)
                    buildString {
                        append("$indent$varName = $value;\n")
                        if (restStatements.isNotEmpty()) {
                            append(compileStatementSequence(restStatements, 0))
                        }
                    }
                }
            }
        } else {
            val value = visitExpression(expr)
            return buildString {
                append("$indent$varName = $value;\n")
                if (restStatements.isNotEmpty()) {
                    append(compileStatementSequence(restStatements, 0))
                }
            }
        }
    }
    
    private fun compileExpressionStatementInSequence(
        ctx: MiniKotlinParser.ExpressionContext,
        restStatements: List<MiniKotlinParser.StatementContext>
    ): String {
        if (containsFunctionCall(ctx)) {
            return when (ctx) {
                is MiniKotlinParser.FunctionCallExprContext -> {
                    val functionName = ctx.IDENTIFIER().text
                    val args = ctx.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
                    val contParamName = "arg${continuationDepth}"
                    continuationDepth++
                    
                    buildString {
                        append("$indent${mapFunctionName(functionName)}(${args.joinToString(", ")}, ($contParamName) -> {\n")
                        indentLevel++
                        
                        if (restStatements.isNotEmpty()) {
                            append(compileStatementSequence(restStatements, 0))
                        }
                        
                        indentLevel--
                        append("$indent});")
                    }.also { continuationDepth-- }
                }
                else -> {
                    buildString {
                        append("$indent${visitExpression(ctx)};\n")
                        if (restStatements.isNotEmpty()) {
                            append(compileStatementSequence(restStatements, 0))
                        }
                    }
                }
            }
        } else {
            return buildString {
                append("$indent${visitExpression(ctx)};\n")
                if (restStatements.isNotEmpty()) {
                    append(compileStatementSequence(restStatements, 0))
                }
            }
        }
    }
    
    private fun compileIfStatementInSequence(
        ctx: MiniKotlinParser.IfStatementContext,
        restStatements: List<MiniKotlinParser.StatementContext>
    ): String {
        val condition = visitExpression(ctx.expression())
        
        indentLevel++
        val thenStatements = ctx.block(0).statement().toList()
        val thenBody = if (thenStatements.isNotEmpty()) {
            compileStatementSequence(thenStatements, 0)
        } else {
            ""
        }
        
        val elseBody = if (ctx.block().size > 1) {
            val elseStatements = ctx.block(1).statement().toList()
            if (elseStatements.isNotEmpty()) {
                compileStatementSequence(elseStatements, 0)
            } else {
                ""
            }
        } else null
        indentLevel--
        
        return buildString {
            append("${indent}if ($condition) {\n")
            append(thenBody)
            if (elseBody != null) {
                append("\n$indent} else {\n")
                append(elseBody)
            }
            append("\n$indent}")
            
            if (restStatements.isNotEmpty()) {
                append("\n")
                append(compileStatementSequence(restStatements, 0))
            }
        }
    }
    
    private fun compileWhileStatementInSequence(
        ctx: MiniKotlinParser.WhileStatementContext,
        restStatements: List<MiniKotlinParser.StatementContext>
    ): String {
        val condition = visitExpression(ctx.expression())
        
        indentLevel++
        val bodyStatements = ctx.block().statement().toList()
        val body = if (bodyStatements.isNotEmpty()) {
            compileStatementSequence(bodyStatements, 0)
        } else {
            ""
        }
        indentLevel--
        
        return buildString {
            append("${indent}while ($condition) {\n")
            append(body)
            append("\n$indent}")
            
            if (restStatements.isNotEmpty()) {
                append("\n")
                append(compileStatementSequence(restStatements, 0))
            }
        }
    }
    
    override fun visitReturnStatement(ctx: MiniKotlinParser.ReturnStatementContext): String {
        val expr = ctx.expression()
        return if (expr != null) {
            if (containsFunctionCall(expr)) {
                compileReturnWithFunctionCall(expr)
            } else {
                val value = visitExpression(expr)
                "${indent}__continuation.accept($value);\n${indent}return;"
            }
        } else {
            "${indent}__continuation.accept(null);\n${indent}return;"
        }
    }
    
    private fun compileReturnWithFunctionCall(expr: MiniKotlinParser.ExpressionContext): String {
        val contParamName = "arg${continuationDepth}"
        continuationDepth++
        
        return when (expr) {
            is MiniKotlinParser.FunctionCallExprContext -> {
                val functionName = expr.IDENTIFIER().text
                val args = expr.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
                
                buildString {
                    append("$indent${mapFunctionName(functionName)}(${args.joinToString(", ")}, ($contParamName) -> {\n")
                    indentLevel++
                    append("${indent}__continuation.accept($contParamName);\n")
                    append("${indent}return;\n")
                    indentLevel--
                    append("$indent});")
                }
            }
            is MiniKotlinParser.MulDivExprContext,
            is MiniKotlinParser.AddSubExprContext -> {
                compileReturnWithBinaryOp(expr, contParamName)
            }
            else -> {
                val value = visitExpression(expr)
                "${indent}__continuation.accept($value);\n${indent}return;"
            }
        }.also {
            continuationDepth--
        }
    }
    
    private fun compileReturnWithBinaryOp(
        expr: MiniKotlinParser.ExpressionContext,
        contParamName: String
    ): String {
        val left = when (expr) {
            is MiniKotlinParser.MulDivExprContext -> expr.expression(0)
            is MiniKotlinParser.AddSubExprContext -> expr.expression(0)
            else -> return "$indent// Unsupported"
        }
        
        val right = when (expr) {
            is MiniKotlinParser.MulDivExprContext -> expr.expression(1)
            is MiniKotlinParser.AddSubExprContext -> expr.expression(1)
            else -> return "$indent// Unsupported"
        }
        
        val op = expr.getChild(1).text
        
        if (containsFunctionCall(right)) {
            val leftStr = visitExpression(left)
            val rightExpr = right as? MiniKotlinParser.FunctionCallExprContext
            
            if (rightExpr != null) {
                val functionName = rightExpr.IDENTIFIER().text
                val args = rightExpr.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
                
                return buildString {
                    append("$indent${mapFunctionName(functionName)}(${args.joinToString(", ")}, ($contParamName) -> {\n")
                    indentLevel++
                    append("${indent}__continuation.accept(($leftStr $op $contParamName));\n")
                    append("${indent}return;\n")
                    indentLevel--
                    append("$indent});")
                }
            }
        }
        
        val value = visitExpression(expr)
        return "${indent}__continuation.accept($value);\n${indent}return;"
    }
    
    private fun visitExpression(ctx: MiniKotlinParser.ExpressionContext): String {
        return when (ctx) {
            is MiniKotlinParser.FunctionCallExprContext -> visitFunctionCallExpr(ctx)
            is MiniKotlinParser.PrimaryExprContext -> visitPrimaryExpr(ctx)
            is MiniKotlinParser.NotExprContext -> "(!${visitExpression(ctx.expression())})"
            is MiniKotlinParser.MulDivExprContext -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                val op = ctx.getChild(1).text
                "($left $op $right)"
            }
            is MiniKotlinParser.AddSubExprContext -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                val op = ctx.getChild(1).text
                "($left $op $right)"
            }
            is MiniKotlinParser.ComparisonExprContext -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                val op = ctx.getChild(1).text
                "($left $op $right)"
            }
            is MiniKotlinParser.EqualityExprContext -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                val op = ctx.getChild(1).text
                "($left $op $right)"
            }
            is MiniKotlinParser.AndExprContext -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                "($left && $right)"
            }
            is MiniKotlinParser.OrExprContext -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                "($left || $right)"
            }
            else -> visit(ctx)
        }
    }
    
    override fun visitPrimaryExpr(ctx: MiniKotlinParser.PrimaryExprContext): String {
        return visitPrimary(ctx.primary())
    }
    
    private fun visitPrimary(ctx: MiniKotlinParser.PrimaryContext): String {
        return when (ctx) {
            is MiniKotlinParser.IntLiteralContext -> ctx.INTEGER_LITERAL().text
            is MiniKotlinParser.StringLiteralContext -> ctx.STRING_LITERAL().text
            is MiniKotlinParser.BoolLiteralContext -> ctx.BOOLEAN_LITERAL().text
            is MiniKotlinParser.IdentifierExprContext -> ctx.IDENTIFIER().text
            is MiniKotlinParser.ParenExprContext -> "(${visitExpression(ctx.expression())})"
            else -> ""
        }
    }
    
    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        val functionName = ctx.IDENTIFIER().text
        val args = ctx.argumentList()?.expression()?.map { visitExpression(it) } ?: emptyList()
        return "${mapFunctionName(functionName)}(${args.joinToString(", ")})"
    }
    
    private fun containsFunctionCall(ctx: ParseTree?): Boolean {
        if (ctx == null) return false
        if (ctx is MiniKotlinParser.FunctionCallExprContext) return true
        for (i in 0 until ctx.childCount) {
            if (containsFunctionCall(ctx.getChild(i))) return true
        }
        return false
    }
    
    private fun mapType(kotlinType: String): String {
        return when (kotlinType) {
            "Int" -> "Integer"
            "String" -> "String"
            "Boolean" -> "Boolean"
            "Unit" -> "Void"
            else -> kotlinType
        }
    }
    
    private fun mapFunctionName(name: String): String {
        return when (name) {
            "println" -> "Prelude.println"
            else -> name
        }
    }
}
