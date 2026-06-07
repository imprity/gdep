package main

import (
	"math"
	"strings"
	"unicode"
	"unicode/utf8"
)

func ScoreClassNameSimilarity(
	filePath, className string,
	mat *Matrix,
) int {
	if filePath == "" || className == "" {
		return math.MinInt
	}

	// normalize slash
	filePath = strings.ReplaceAll(filePath, "\\", "/")

	// remove file extension from filePath
	{
		slashIndex := strings.LastIndexByte(filePath, '/')
		if slashIndex < 0 {
			slashIndex = 0
		}

		dotIndex := -1
		for i := slashIndex + 1; i < len(filePath); i++ {
			if filePath[i] == '.' {
				dotIndex = i
				break
			}
		}

		if dotIndex >= 0 {
			filePath = filePath[0:dotIndex]
		}
	}

	filePathParts := strings.Split(filePath, "/")
	classNameParts := strings.Split(className, ".")

	score := 0

	filePathAbbreviated := GetAbbreviated(filePathParts)
	classNameAbbreviated := GetAbbreviated(classNameParts)

	score -= FuzzyMatch(filePathAbbreviated, classNameAbbreviated, mat).Distance * 10

	filePath2 := strings.ReplaceAll(filePath, "/", ".")
	className2 := RemoveLogNoise(classNameParts)

	{
		var str string
		var sub string

		if len(filePath2) > len(className2) {
			str = filePath2
			sub = className2
		} else {
			str = className2
			sub = filePath2
		}

		res := FuzzyMatch(str, sub, mat)

		score -= res.Distance * 10
		score -= int(AbsInt(len(str) - res.Length()))
	}

	return score
}

func GetAbbreviated(parts []string) string {
	sb := strings.Builder{}

	for i := 0; i < len(parts); i++ {
		part := parts[i]

		r, _ := utf8.DecodeRuneInString(part)
		if r != utf8.RuneError {
			sb.WriteRune(unicode.ToLower(r))
		}

		if i+1 < len(parts) {
			sb.WriteString(".")
		}
	}

	return sb.String()
}

func RemoveLogNoise(classNameParts []string) string {
	sb := strings.Builder{}

	abbrevEnded := false

	for i := 0; i < len(classNameParts); i++ {
		part := classNameParts[i]

		if len(part) > 1 {
			abbrevEnded = true
		}

		if !abbrevEnded {
			continue
		}

		if i == len(classNameParts)-1 {
			dollarIndex := strings.IndexByte(part, '$')

			if dollarIndex >= 0 {
				part = part[0:dollarIndex]
			}
			sb.WriteString(part)
		} else {
			sb.WriteString(part)
			sb.WriteString(".")
		}
	}

	return sb.String()
}
