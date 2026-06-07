package main

import (
	"math"
)

type Matrix struct {
	Width  int
	Height int

	Values []int
}

func NewMatrix(initCapacity int) *Matrix {
	return &Matrix{
		Width:  0,
		Height: 0,
		Values: make([]int, initCapacity),
	}
}

func (m *Matrix) PrepareForUse(width, height int) {
	m.Width = width
	m.Height = height

	if len(m.Values) < width*height {
		newCapacity := 2

		for newCapacity < width*height {
			newCapacity *= 2
		}

		m.Values = make([]int, newCapacity)
	} else {
		clear(m.Values)
	}
}

func (m *Matrix) SetValue(x, y, to int) {
	m.Values[x+y*m.Width] = to
}

func (m *Matrix) GetValue(x, y int) int {
	return m.Values[x+y*m.Width]
}

type FuzzyMatchResult struct {
	Begin    int // where the sub string begins in str
	End      int // where the sub string ends in str
	Distance int
}

func (fm *FuzzyMatchResult) Length() int {
	return fm.End - fm.Begin
}

// Implementation of fuzzy match algorithm from wikipedia.
// https://en.wikipedia.org/wiki/Approximate_string_matching#Problem_formulation_and_algorithms
//
// According to the article, it's from the paper
// Sellers, Peter H. (1980). "The Theory and Computation of Evolutionary Distances: Pattern Recognition". Journal of
// Algorithms. 1 (4): 359–73. doi:10.1016/0196-6774(80)90016-4.
//
// But I'm not 100% sure.
func FuzzyMatch(str, sub string, mat *Matrix) FuzzyMatchResult {
	// we need to delete every character of sub to be str
	if str == "" {
		return FuzzyMatchResult{
			Begin:    0,
			End:      0,
			Distance: len(sub),
		}
	}

	if sub == "" {
		return FuzzyMatchResult{
			Begin:    0,
			End:      0,
			Distance: 0,
		}
	}

	width := len(str) + 1
	height := len(sub) + 1

	mat.PrepareForUse(width, height)

	for y := 1; y < height; y++ {
		mat.SetValue(0, y, y)
	}

	for y := 1; y < height; y++ {
		for x := 1; x < width; x++ {
			c := str[x-1]
			subC := sub[y-1]

			if c == subC {
				mat.SetValue(x, y, mat.GetValue(x-1, y-1))
			} else {
				mat.SetValue(x, y, 1+min(
					mat.GetValue(x, y-1),
					mat.GetValue(x-1, y),
					mat.GetValue(x-1, y-1)),
				)
			}
		}
	}

	minCol := 0
	minDist := math.MaxInt
	for x := 0; x < width; x++ {
		dist := mat.GetValue(x, height-1)
		if dist < minDist {
			minCol = x
			minDist = dist
		}
	}

	// there are no matching characters
	// so for every characters in sub,
	// we need to change/add characters in str
	//
	// so the distance length of sub
	if minCol == 0 {
		return FuzzyMatchResult{
			Begin:    0,
			End:      0,
			Distance: len(sub),
		}
	}

	posX := minCol
	posY := height - 1

	for {
		diag := mat.GetValue(posX-1, posY-1)
		cur := mat.GetValue(posX, posY)

		if diag == cur {
			posX -= 1
			posY -= 1
		} else {
			up := mat.GetValue(posX, posY-1)
			left := mat.GetValue(posX-1, posY)

			minV := min(up, left, diag)

			if up == minV {
				posY -= 1
			} else if diag == minV {
				posX -= 1
				posY -= 1
			} else { // left == min
				posX -= 1
			}
		}

		if posX <= 0 || posY <= 0 {
			break
		}
	}

	return FuzzyMatchResult{
		Begin:    posX,
		End:      minCol,
		Distance: minDist,
	}
}
