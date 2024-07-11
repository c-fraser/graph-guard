/*
Copyright 2023 c-fraser

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
grammar Schema;

@ header
{
package io.github.cfraser.graphguard.validate;
}
start
   : graph+ EOF
   ;

graph
   : GRAPH name LC (node SEMICOLON)* RC
   ;

node
   : NODE metadata? name properties? (COLON relationship (COMMA relationship)*)?
   ;

relationship
   : metadata? name properties? (UNDIRECTED | DIRECTED) target
   ;

properties
   : LP (property (COMMA property)*)? RP
   ;

property
   : metadata? name COLON (type | union)
   ;

type
   : (typeValue | list | stringLiteral) QM?
   ;

typeValue
   : TYPE_VALUE
   ;

list
   : LIST LT typeValue QM? GT
   ;

stringLiteral
   : STRING_LITERAL
   ;

union
   : type (PIPE type)+
   ;

name
   : NAME
   ;

qualified
   : QUALIFIED
   ;

target
   : name
   | qualified
   ;

metadata
   : AT name metadataValue? metadata?
   ;

metadataValue
   : LP (stringLiteral) RP
   ;

AT
   : '@'
   ;

LC
   : '{'
   ;

RC
   : '}'
   ;

SEMICOLON
   : ';'
   ;

LP
   : '('
   ;

RP
   : ')'
   ;

COMMA
   : ','
   ;

COLON
   : ':'
   ;

LT
   : '<'
   ;

GT
   : '>'
   ;

QM
   : '?'
   ;

DOT
   : '.'
   ;

PIPE
   : '|'
   ;

UNDIRECTED
   : '--'
   ;

DIRECTED
   : '->'
   ;

GRAPH
   : 'graph'
   ;

NODE
   : 'node'
   ;

TYPE_VALUE
   : ANY
   | BOOLEAN
   | DATE
   | DATE_TIME
   | DURATION
   | FLOAT
   | INTEGER
   | LOCAL_DATE_TIME
   | LOCAL_TIME
   | STRING
   | TIME
   ;

ANY
   : 'Any'
   ;

BOOLEAN
   : 'Boolean'
   ;

DATE
   : 'Date'
   ;

DATE_TIME
   : 'DateTime'
   ;

DURATION
   : 'Duration'
   ;

FLOAT
   : 'Float'
   ;

INTEGER
   : 'Integer'
   ;

LOCAL_DATE_TIME
   : 'LocalDateTime'
   ;

LOCAL_TIME
   : 'LocalTime'
   ;

STRING
   : 'String'
   ;

TIME
   : 'Time'
   ;

LIST
   : 'List'
   ;

NAME
   : [_A-Za-z] [_0-9A-Za-z]*
   ;

QUALIFIED
   : NAME DOT NAME
   ;

STRING_LITERAL
   : '"' (~ ["\\\r\n] | '\'' | '\\"')* '"'
   ;

WHITESPACE
   : [ \t\n\r]+ -> skip
   ;

COMMENT
   : '//' ~ [\r\n]* -> channel (HIDDEN)
   ;

