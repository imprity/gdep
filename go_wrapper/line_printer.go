package main

import (
	"strings"
)

type LinePrinter struct {
	AddDelimiter bool
	Sb           strings.Builder
	Delimiter    string
}

func NewLinePrinter(delimiter string) LinePrinter {
	lp := LinePrinter{}
	lp.Delimiter = delimiter

	return lp
}

func (lp *LinePrinter) PrintString(toPrint string) {
	if lp.AddDelimiter {
		lp.Sb.WriteString(lp.Delimiter)
	}
	lp.Sb.WriteString(toPrint)
	lp.AddDelimiter = true
}

func (lp *LinePrinter) String() string {
	return lp.Sb.String()
}
