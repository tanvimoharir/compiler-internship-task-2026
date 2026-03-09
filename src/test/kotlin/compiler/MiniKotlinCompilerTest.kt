package org.example.compiler

import MiniKotlinLexer
import MiniKotlinParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MiniKotlinCompilerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun parseString(source: String): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromString(source)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromPath(path)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar = devPath.toFile().listFiles()
                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    private fun compileAndRun(sampleFile: String): String {
        val examplePath = Paths.get(sampleFile)
        val program = parseFile(examplePath)

        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        assertIs<CompilationResult.Success>(compilationResult, "Compilation failed")
        assertIs<ExecutionResult.Success>(executionResult, "Execution failed")

        return executionResult.stdout.trim()
    }

    @Test
    fun `compile example_mini outputs 120 and 15`() {
        val output = compileAndRun("samples/example.mini")
        assertTrue(output.contains("120"), "Expected output to contain factorial result 120, but got: $output")
        assertTrue(output.contains("15"), "Expected output to contain arithmetic result 15, but got: $output")
    }

    @Test
    fun `test fibonacci computes fib(6) = 8`() {
        val output = compileAndRun("samples/test_fibonacci.mini")
        assertTrue(output.contains("8"), "Expected fib(6) = 8, but got: $output")
    }

    @Test
    fun `test nested function calls double(triple(5)) = 30`() {
        val output = compileAndRun("samples/test_nested_calls.mini")
        assertTrue(output.contains("30"), "Expected double(triple(5)) = 30, but got: $output")
    }

    @Test
    fun `test complex expressions`() {
        val output = compileAndRun("samples/test_complex_expr.mini")
        val lines = output.lines()
        assertTrue(lines[0] == "10", "Expected compute(2,3,4) = 10, but got: ${lines[0]}")
        assertTrue(lines[1] == "25", "Expected 10*2+5 = 25, but got: ${lines[1]}")
    }

    @Test
    fun `test conditionals with function calls`() {
        val output = compileAndRun("samples/test_conditionals.mini")
        assertTrue(output.contains("1"), "Expected isEven(10) = true (1), but got: $output")
    }

    @Test
    fun `test boolean logic operations`() {
        val output = compileAndRun("samples/test_boolean_logic.mini")
        assertTrue(output.contains("1"), "Expected isPositive(5) && isNegative(-3) = true (1), but got: $output")
    }

    @Test
    fun `test arithmetic with function calls`() {
        val output = compileAndRun("samples/test_arithmetic.mini")
        assertTrue(output.contains("14"), "Expected (3+4)*2 = 14, but got: $output")
    }

    @Test
    fun `test while loop with function calls`() {
        val output = compileAndRun("samples/test_while_loop.mini")
        assertTrue(output == "6", "Expected sum 1+2+3 = 6, but got: $output")
    }

    @Test
    fun `test simple arithmetic without function calls`() {
        val source = """
            fun main(): Unit {
                var x: Int = 5 + 3
                var y: Int = x * 2
                println(y)
            }
        """.trimIndent()
        
        val program = parseString(source)
        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)
        
        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)
        
        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)
        
        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)
        
        val output = executionResult.stdout.trim()
        assertTrue(output == "16", "Expected 16, but got: $output")
    }

    @Test
    fun `test multiple parameters`() {
        val source = """
            fun add3(a: Int, b: Int, c: Int): Int {
                return a + b + c
            }
            
            fun main(): Unit {
                var result: Int = add3(1, 2, 3)
                println(result)
            }
        """.trimIndent()
        
        val program = parseString(source)
        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)
        
        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)
        
        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)
        
        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)
        
        val output = executionResult.stdout.trim()
        assertTrue(output == "6", "Expected 6, but got: $output")
    }
}
