/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search.spell;

import java.io.IOException;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * A spell checker whose sole function is to offer suggestions by combining multiple terms into one
 * word and/or breaking terms into multiple words.
 */
public class WordBreakSpellChecker {
  private int minSuggestionFrequency = 1;
  private int minBreakWordLength = 1;
  private int maxCombineWordLength = 20;
  private int maxChanges = 1;
  private int maxEvaluations = 1000;

  /** Term that can be used to prohibit adjacent terms from being combined */
  public static final Term SEPARATOR_TERM = new Term("", "");

  /**
   * Creates a new spellchecker with default configuration values
   *
   * @see #setMaxChanges(int)
   * @see #setMaxCombineWordLength(int)
   * @see #setMaxEvaluations(int)
   * @see #setMinBreakWordLength(int)
   * @see #setMinSuggestionFrequency(int)
   */
  public WordBreakSpellChecker() {}

  /** Determines the order to list word break suggestions */
  public enum BreakSuggestionSortMethod {
    /** Sort by Number of word breaks, then by the Sum of all the component term's frequencies */
    NUM_CHANGES_THEN_SUMMED_FREQUENCY,
    /**
     * Sort by Number of word breaks, then by the Maximum of all the component term's frequencies
     */
    NUM_CHANGES_THEN_MAX_FREQUENCY
  }

  /**
   * Generate suggestions by breaking the passed-in term into multiple words. The scores returned
   * are equal to the number of word breaks needed so a lower score is generally preferred over a
   * higher score.
   *
   * @param suggestMode - default = {@link SuggestMode#SUGGEST_WHEN_NOT_IN_INDEX}
   * @param sortMethod - default = {@link BreakSuggestionSortMethod#NUM_CHANGES_THEN_MAX_FREQUENCY}
   * @return one or more arrays of words formed by breaking up the original term
   * @throws IOException If there is a low-level I/O error.
   */
  public SuggestWord[][] suggestWordBreaks(
      Term term,
      int maxSuggestions,
      IndexReader ir,
      SuggestMode suggestMode,
      BreakSuggestionSortMethod sortMethod)
      throws IOException {
    if (maxSuggestions < 1) {
      return new SuggestWord[0][0];
    }
    if (suggestMode == null) {
      suggestMode = SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX;
    }
    if (sortMethod == null) {
      sortMethod = BreakSuggestionSortMethod.NUM_CHANGES_THEN_MAX_FREQUENCY;
    }

    int queueInitialCapacity = maxSuggestions > 10 ? 10 : maxSuggestions;
    Comparator<SuggestWordArrayWrapper> queueComparator =
        sortMethod == BreakSuggestionSortMethod.NUM_CHANGES_THEN_MAX_FREQUENCY
            ? new LengthThenMaxFreqComparator()
            : new LengthThenSumFreqComparator();
    Queue<SuggestWordArrayWrapper> suggestions =
        new PriorityQueue<>(queueInitialCapacity, queueComparator);

    int origFreq = ir.docFreq(term);
    if (origFreq > 0 && suggestMode == SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX) {
      return new SuggestWord[0][];
    }

    int useMinSuggestionFrequency = minSuggestionFrequency;
    if (suggestMode == SuggestMode.SUGGEST_MORE_POPULAR) {
      useMinSuggestionFrequency = (origFreq == 0 ? 1 : origFreq);
    }

    generateBreakUpSuggestions(
        term,
        ir,
        1,
        maxSuggestions,
        useMinSuggestionFrequency,
        new SuggestWord[0],
        suggestions,
        0,
        sortMethod);

    SuggestWord[][] suggestionArray = new SuggestWord[suggestions.size()][];
    for (int i = suggestions.size() - 1; i >= 0; i--) {
      suggestionArray[i] = suggestions.remove().suggestWords;
    }

    return suggestionArray;
  }

  /**
   * Generate suggestions by combining one or more of the passed-in terms into single words. The
   * returned {@link CombineSuggestion} contains both a {@link SuggestWord} and also an array
   * detailing which passed-in terms were involved in creating this combination. The scores returned
   * are equal to the number of word combinations needed, also one less than the length of the array
   * {@link CombineSuggestion#originalTermIndexes()}. Generally, a suggestion with a lower score is
   * preferred over a higher score.
   *
   * <p>To prevent two adjacent terms from being combined (for instance, if one is mandatory and the
   * other is prohibited), separate the two terms with {@link WordBreakSpellChecker#SEPARATOR_TERM}
   *
   * <p>When suggestMode equals {@link SuggestMode#SUGGEST_WHEN_NOT_IN_INDEX}, each suggestion will
   * include at least one term not in the index.
   *
   * <p>When suggestMode equals {@link SuggestMode#SUGGEST_MORE_POPULAR}, each suggestion will have
   * the same, or better frequency than the most-popular included term.
   *
   * @return an array of words generated by combining original terms
   * @throws IOException If there is a low-level I/O error.
   */
  public CombineSuggestion[] suggestWordCombinations(
      Term[] terms, int maxSuggestions, IndexReader ir, SuggestMode suggestMode)
      throws IOException {
    if (maxSuggestions < 1) {
      return new CombineSuggestion[0];
    }

    int[] origFreqs = null;
    if (suggestMode != SuggestMode.SUGGEST_ALWAYS) {
      origFreqs = new int[terms.length];
      for (int i = 0; i < terms.length; i++) {
        origFreqs[i] = ir.docFreq(terms[i]);
      }
    }

    int queueInitialCapacity = maxSuggestions > 10 ? 10 : maxSuggestions;
    Comparator<CombineSuggestionWrapper> queueComparator = new CombinationsThenFreqComparator();
    Queue<CombineSuggestionWrapper> suggestions =
        new PriorityQueue<>(queueInitialCapacity, queueComparator);

    int thisTimeEvaluations = 0;
    for (int i = 0; i < terms.length - 1; i++) {
      if (terms[i].equals(SEPARATOR_TERM)) {
        continue;
      }
      String leftTermText = terms[i].text();
      int leftTermLength = leftTermText.codePointCount(0, leftTermText.length());
      if (leftTermLength > maxCombineWordLength) {
        continue;
      }
      int maxFreq = 0;
      int minFreq = Integer.MAX_VALUE;
      if (origFreqs != null) {
        maxFreq = origFreqs[i];
        minFreq = origFreqs[i];
      }
      String combinedTermText = leftTermText;
      int combinedLength = leftTermLength;
      for (int j = i + 1; j < terms.length && j - i <= maxChanges; j++) {
        if (terms[j].equals(SEPARATOR_TERM)) {
          break;
        }
        String rightTermText = terms[j].text();
        int rightTermLength = rightTermText.codePointCount(0, rightTermText.length());
        combinedTermText += rightTermText;
        combinedLength += rightTermLength;
        if (combinedLength > maxCombineWordLength) {
          break;
        }

        if (origFreqs != null) {
          maxFreq = Math.max(maxFreq, origFreqs[j]);
          minFreq = Math.min(minFreq, origFreqs[j]);
        }

        Term combinedTerm = new Term(terms[0].field(), combinedTermText);
        int combinedTermFreq = ir.docFreq(combinedTerm);

        if (suggestMode != SuggestMode.SUGGEST_MORE_POPULAR || combinedTermFreq >= maxFreq) {
          if (suggestMode != SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX || minFreq == 0) {
            if (combinedTermFreq >= minSuggestionFrequency) {
              int[] origIndexes = new int[j - i + 1];
              origIndexes[0] = i;
              for (int k = 1; k < origIndexes.length; k++) {
                origIndexes[k] = i + k;
              }
              SuggestWord word = new SuggestWord();
              word.freq = combinedTermFreq;
              word.score = origIndexes.length - 1;
              word.string = combinedTerm.text();
              CombineSuggestionWrapper suggestion =
                  new CombineSuggestionWrapper(
                      new CombineSuggestion(word, origIndexes), (origIndexes.length - 1));
              suggestions.offer(suggestion);
              if (suggestions.size() > maxSuggestions) {
                suggestions.poll();
              }
            }
          }
        }
        thisTimeEvaluations++;
        if (thisTimeEvaluations == maxEvaluations) {
          break;
        }
      }
    }
    CombineSuggestion[] combineSuggestions = new CombineSuggestion[suggestions.size()];
    for (int i = suggestions.size() - 1; i >= 0; i--) {
      combineSuggestions[i] = suggestions.remove().combineSuggestion;
    }
    return combineSuggestions;
  }

  private int generateBreakUpSuggestions(
      Term term,
      IndexReader ir,
      int numberBreaks,
      int maxSuggestions,
      int useMinSuggestionFrequency,
      SuggestWord[] prefix,
      Queue<SuggestWordArrayWrapper> suggestions,
      int totalEvaluations,
      BreakSuggestionSortMethod sortMethod)
      throws IOException {
    final String termText = term.text();
    final int termLength = termText.codePointCount(0, termText.length());
    final int useMinBreakWordLength = Math.max(minBreakWordLength, 1);

    if (termLength < (useMinBreakWordLength * 2)) {
      return totalEvaluations;
    }

    // Two phase breadth first search.
    //
    // Phase #1: checking for bi-sects of our termText, and recording the
    // positions of valid leftText splits for later

    final int maxSplitPosition = termLength - useMinBreakWordLength;
    final BitSet validLeftSplitPositions = new FixedBitSet(termText.length());

    for (int i = useMinBreakWordLength; i <= maxSplitPosition; i++) {
      if (totalEvaluations >= maxEvaluations) {
        return totalEvaluations;
      }
      totalEvaluations++;

      final int end = termText.offsetByCodePoints(0, i);
      final String leftText = termText.substring(0, end);
      final String rightText = termText.substring(end);

      final SuggestWord leftWord = generateSuggestWord(ir, term.field(), leftText);
      if (leftWord.freq >= useMinSuggestionFrequency) {
        validLeftSplitPositions.set(end);
        final SuggestWord rightWord = generateSuggestWord(ir, term.field(), rightText);
        if (rightWord.freq >= useMinSuggestionFrequency) {
          suggestions.offer(
              new SuggestWordArrayWrapper(newSuggestion(prefix, leftWord, rightWord)));
          if (suggestions.size() > maxSuggestions) {
            suggestions.poll();
          }
        }
      }
    }

    // if we are about to exceed our maxChanges *OR* we have a full list of
    // suggestions, we can return now.
    //
    // (because any subsequent suggestions are garunteed to have more changes
    // then anything currently in the queue, and not be competitive)

    final int newNumberBreaks = numberBreaks + 1;
    if (totalEvaluations >= maxEvaluations
        || newNumberBreaks > maxChanges
        || suggestions.size() >= maxSuggestions) {
      return totalEvaluations;
    }

    // Phase #2: recursing on the right side of any valid leftText terms
    final BitSetIterator leftIter = new BitSetIterator(validLeftSplitPositions, 0);
    for (int pos = leftIter.nextDoc();
        pos != BitSetIterator.NO_MORE_DOCS;
        pos = leftIter.nextDoc()) {
      final String leftText = termText.substring(0, pos);
      final String rightText = termText.substring(pos);
      final SuggestWord leftWord = generateSuggestWord(ir, term.field(), leftText);
      totalEvaluations =
          generateBreakUpSuggestions(
              new Term(term.field(), rightText),
              ir,
              newNumberBreaks,
              maxSuggestions,
              useMinSuggestionFrequency,
              newPrefix(prefix, leftWord),
              suggestions,
              totalEvaluations,
              sortMethod);
      if (totalEvaluations >= maxEvaluations) {
        break;
      }
    }

    return totalEvaluations;
  }

  private SuggestWord[] newPrefix(SuggestWord[] oldPrefix, SuggestWord append) {
    SuggestWord[] newPrefix = new SuggestWord[oldPrefix.length + 1];
    System.arraycopy(oldPrefix, 0, newPrefix, 0, oldPrefix.length);
    newPrefix[newPrefix.length - 1] = append;
    return newPrefix;
  }

  private SuggestWord[] newSuggestion(
      SuggestWord[] prefix, SuggestWord append1, SuggestWord append2) {
    SuggestWord[] newSuggestion = new SuggestWord[prefix.length + 2];
    int score = prefix.length + 1;
    for (int i = 0; i < prefix.length; i++) {
      SuggestWord word = new SuggestWord();
      word.string = prefix[i].string;
      word.freq = prefix[i].freq;
      word.score = score;
      newSuggestion[i] = word;
    }
    append1.score = score;
    append2.score = score;
    newSuggestion[newSuggestion.length - 2] = append1;
    newSuggestion[newSuggestion.length - 1] = append2;
    return newSuggestion;
  }

  private SuggestWord generateSuggestWord(IndexReader ir, String fieldname, String text)
      throws IOException {
    Term term = new Term(fieldname, text);
    int freq = ir.docFreq(term);
    SuggestWord word = new SuggestWord();
    word.freq = freq;
    word.score = 1;
    word.string = text;
    return word;
  }

  /**
   * Returns the minimum frequency a term must have to be part of a suggestion.
   *
   * @see #setMinSuggestionFrequency(int)
   */
  public int getMinSuggestionFrequency() {
    return minSuggestionFrequency;
  }

  /**
   * Returns the maximum length of a combined suggestion
   *
   * @see #setMaxCombineWordLength(int)
   */
  public int getMaxCombineWordLength() {
    return maxCombineWordLength;
  }

  /**
   * Returns the minimum size of a broken word
   *
   * @see #setMinBreakWordLength(int)
   */
  public int getMinBreakWordLength() {
    return minBreakWordLength;
  }

  /**
   * Returns the maximum number of changes to perform on the input
   *
   * @see #setMaxChanges(int)
   */
  public int getMaxChanges() {
    return maxChanges;
  }

  /**
   * Returns the maximum number of word combinations to evaluate.
   *
   * @see #setMaxEvaluations(int)
   */
  public int getMaxEvaluations() {
    return maxEvaluations;
  }

  /**
   * The minimum frequency a term must have to be included as part of a suggestion. Default=1 Not
   * applicable when used with {@link SuggestMode#SUGGEST_MORE_POPULAR}
   *
   * @see #getMinSuggestionFrequency()
   */
  public void setMinSuggestionFrequency(int minSuggestionFrequency) {
    this.minSuggestionFrequency = minSuggestionFrequency;
  }

  /**
   * The maximum length of a suggestion made by combining 1 or more original terms. Default=20
   *
   * @see #getMaxCombineWordLength()
   */
  public void setMaxCombineWordLength(int maxCombineWordLength) {
    this.maxCombineWordLength = maxCombineWordLength;
  }

  /**
   * The minimum length to break words down to. Default=1
   *
   * @see #getMinBreakWordLength()
   */
  public void setMinBreakWordLength(int minBreakWordLength) {
    this.minBreakWordLength = minBreakWordLength;
  }

  /**
   * The maximum numbers of changes (word breaks or combinations) to make on the original term(s).
   * Default=1
   *
   * @see #getMaxChanges()
   */
  public void setMaxChanges(int maxChanges) {
    this.maxChanges = maxChanges;
  }

  /**
   * The maximum number of word combinations to evaluate. Default=1000. A higher value might improve
   * result quality. A lower value might improve performance.
   *
   * @see #getMaxEvaluations()
   */
  public void setMaxEvaluations(int maxEvaluations) {
    this.maxEvaluations = maxEvaluations;
  }

  private static class LengthThenMaxFreqComparator implements Comparator<SuggestWordArrayWrapper> {
    @Override
    public int compare(SuggestWordArrayWrapper o1, SuggestWordArrayWrapper o2) {
      if (o1.suggestWords.length != o2.suggestWords.length) {
        return o2.suggestWords.length - o1.suggestWords.length;
      }
      if (o1.freqMax != o2.freqMax) {
        return o1.freqMax - o2.freqMax;
      }
      return 0;
    }
  }

  private static class LengthThenSumFreqComparator implements Comparator<SuggestWordArrayWrapper> {
    @Override
    public int compare(SuggestWordArrayWrapper o1, SuggestWordArrayWrapper o2) {
      if (o1.suggestWords.length != o2.suggestWords.length) {
        return o2.suggestWords.length - o1.suggestWords.length;
      }
      if (o1.freqSum != o2.freqSum) {
        return o1.freqSum - o2.freqSum;
      }
      return 0;
    }
  }

  private static class CombinationsThenFreqComparator
      implements Comparator<CombineSuggestionWrapper> {
    @Override
    public int compare(CombineSuggestionWrapper o1, CombineSuggestionWrapper o2) {
      if (o1.numCombinations != o2.numCombinations) {
        return o2.numCombinations - o1.numCombinations;
      }
      if (o1.combineSuggestion.suggestion().freq != o2.combineSuggestion.suggestion().freq) {
        return o1.combineSuggestion.suggestion().freq - o2.combineSuggestion.suggestion().freq;
      }
      return 0;
    }
  }

  private static class SuggestWordArrayWrapper {
    final SuggestWord[] suggestWords;
    final int freqMax;
    final int freqSum;

    SuggestWordArrayWrapper(SuggestWord[] suggestWords) {
      this.suggestWords = suggestWords;
      int aFreqSum = 0;
      int aFreqMax = 0;
      for (SuggestWord sw : suggestWords) {
        aFreqSum += sw.freq;
        aFreqMax = Math.max(aFreqMax, sw.freq);
      }
      this.freqSum = aFreqSum;
      this.freqMax = aFreqMax;
    }
  }

  private record CombineSuggestionWrapper(
      CombineSuggestion combineSuggestion, int numCombinations) {}
}
