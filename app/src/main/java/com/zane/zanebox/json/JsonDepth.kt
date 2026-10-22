package com.zane.zanebox.json

/** Match Android JSONTokener's quotes, comments and unquoted literals before recursive parsing. */
internal fun requireJsonDepth(text:String) {
    val stack=CharArray(128)
    var depth=0;var quote='\u0000';var escaped=false;var literal=false;var i=if(text.startsWith('\uFEFF'))1 else 0
    while(i<text.length) {
        val c=text[i]
        if(quote!='\u0000') {
            if(escaped)escaped=false else if(c=='\\')escaped=true else if(c==quote)quote='\u0000'
            i++;continue
        }
        // A quote inside an unquoted literal is ordinary text to Android's JSONTokener.
        if(literal && c !in "{}[]/\\:,=;# \t\u000c\r\n") { i++;continue }
        literal=false
        when(c) {
            '\'', '"' -> quote=c
            '#' -> { while(i<text.length && text[i]!='\n' && text[i]!='\r')i++;continue }
            '/' -> if(i+1<text.length) when(text[i+1]) {
                '/' -> { i+=2;while(i<text.length && text[i]!='\n' && text[i]!='\r')i++;continue }
                '*' -> { val end=text.indexOf("*/",i+2);require(end>=0) { "JSON注释不完整" };i=end+2;continue }
            }
            '[','{' -> { require(depth<stack.size) { "JSON嵌套超过128层" };stack[depth++]=c }
            ']','}' -> { require(depth>0 && stack[depth-1]==if(c==']')'[' else '{') { "JSON结构无效" };depth-- }
            else -> if(c !in " \t\r\n:,;=\\" && !(c=='>' && i>0 && text[i-1] in ":="))literal=true
        }
        i++
    }
    require(depth==0 && quote=='\u0000') { "JSON不完整" }
}
