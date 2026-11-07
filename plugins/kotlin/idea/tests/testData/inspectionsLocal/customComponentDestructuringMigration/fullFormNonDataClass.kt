// PROBLEM: none
// COMPILER_ARGUMENTS: -Xname-based-destructuring=only-syntax

class Foo(val bar: String)

fun foo() {
    (val b<caret>ar) = Foo("")
}
