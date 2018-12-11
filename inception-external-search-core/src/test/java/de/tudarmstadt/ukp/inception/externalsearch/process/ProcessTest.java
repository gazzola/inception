/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.externalsearch.process;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newDirectoryStream;
import static java.nio.file.Files.readAllBytes;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.apache.uima.UIMAFramework.getResourceSpecifierFactory;
import static org.apache.uima.cas.CAS.TYPE_NAME_ANNOTATION;
import static org.apache.uima.cas.CAS.TYPE_NAME_DOUBLE;
import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngine;
import static org.apache.uima.fit.factory.JCasFactory.createJCas;
import static org.apache.uima.fit.factory.TypeSystemDescriptionFactory.createTypeSystemDescription;
import static org.apache.uima.util.CasCreationUtils.mergeTypeSystems;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.CasUtil;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.metadata.TypeDescription;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.junit.Test;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.tokit.BreakIteratorSegmenter;

public class ProcessTest
{
    public static String TYPE_NAME_UNIT = "Unit";
    public static String TYPE_NAME_AGGREGATE = "Aggregate";
    public static String FEATURE_NAME_SCORE = "score";
    
    @Test
    public void test() throws Exception
    {
        // Parameters
        String queryword = "test";
        
        // Set up custom type system
        TypeSystemDescription customTypes = getResourceSpecifierFactory()
                .createTypeSystemDescription();
        TypeDescription tdUnit = customTypes.addType(TYPE_NAME_UNIT, "",
                TYPE_NAME_ANNOTATION);
        tdUnit.addFeature(FEATURE_NAME_SCORE, "", TYPE_NAME_DOUBLE);
        TypeDescription tdAggregate = customTypes.addType(TYPE_NAME_AGGREGATE, "",
                TYPE_NAME_ANNOTATION);
        tdAggregate.addFeature(FEATURE_NAME_SCORE, "", TYPE_NAME_DOUBLE);

        // Set up processing components
        AnalysisEngine splitter = createEngine(BreakIteratorSegmenter.class);
        AnalysisEngine marker = createEngine(UnitByQueryWordAnnotator.class, 
                UnitByQueryWordAnnotator.PARAM_QUERY_WORD, queryword);
        AnalysisEngine scorer = createEngine(GoodnessScoreAnnotator.class, 
                UnitByQueryWordAnnotator.PARAM_QUERY_WORD, queryword);
        AnalysisEngine aggregator = createEngine(AggregateScoreAnnotator.class);
        
        // Create CAS
        JCas doc = createJCas(mergeTypeSystems(asList(customTypes, createTypeSystemDescription())));

        // Process text files
        List<Pair<Path, Double>> scores = new ArrayList<>();
        DirectoryStream<Path> directoryStream = newDirectoryStream(
                Paths.get("src/test/resources/texts"));
        for (Path p : directoryStream) {
            // Clear contents so we can process the next file
            doc.reset();
            doc.setDocumentText(new String(readAllBytes(p), UTF_8));
            
            // Annotate sentences and tokens
            splitter.process(doc);

            // Annotate sentences containing the query word
            marker.process(doc);
            
            // Annotate units with goodness score
            scorer.process(doc);
            
            // Aggregate units scores
            aggregator.process(doc);
            
            // Extract score
            Type tAggregate = doc.getTypeSystem().getType(TYPE_NAME_AGGREGATE);
            Feature fAggregateScore = tAggregate.getFeatureByBaseName(FEATURE_NAME_SCORE);
            FeatureStructure aggregate = CasUtil.selectSingle(doc.getCas(), tAggregate);
            scores.add(Pair.of(p, aggregate.getDoubleValue(fAggregateScore)));
        }
        
        // Assertions checking that proper data has been extracted
        assertThat(scores).hasSize(1);
        assertThat(scores).extracting(Pair::getValue).allMatch(score -> score > 0);
    }
    
    public static class UnitByQueryWordAnnotator
        extends JCasAnnotator_ImplBase
    {
        public static final String PARAM_QUERY_WORD = "queryWord";
        @ConfigurationParameter(name = PARAM_QUERY_WORD, mandatory = true)
        private String queryWord;

        @Override
        public void process(JCas aJCas) throws AnalysisEngineProcessException
        {
            Type tUnit = aJCas.getTypeSystem().getType(TYPE_NAME_UNIT);
            for (Sentence sentence : JCasUtil.select(aJCas, Sentence.class)) {
                for (Token token : JCasUtil.selectCovered(Token.class, sentence)) {
                    if (token.getCoveredText().equals(queryWord)) {
                        AnnotationFS unit = aJCas.getCas().createAnnotation(tUnit,
                                token.getBegin(), token.getEnd());
                        aJCas.getCas().addFsToIndexes(unit);
                    }
                }
            }
        }
    }
    
    public static class GoodnessScoreAnnotator
        extends JCasAnnotator_ImplBase
    {
        public static final String PARAM_QUERY_WORD = "queryWord";
        @ConfigurationParameter(name = PARAM_QUERY_WORD, mandatory = true)
        private String queryWord;
        
        @Override
        public void process(JCas aJCas) throws AnalysisEngineProcessException
        {
            Type tUnit = aJCas.getTypeSystem().getType(TYPE_NAME_UNIT);
            Feature fScore = tUnit.getFeatureByBaseName(FEATURE_NAME_SCORE);
            for (AnnotationFS unit : CasUtil.select(aJCas.getCas(), tUnit)) {
                List<String> tokens = JCasUtil.selectCovered(Token.class, unit).stream()
                        .map(AnnotationFS::getCoveredText).collect(toList());
                unit.setDoubleValue(fScore, getScore(queryWord, tokens));
            }
        }

        private double getScore(String aQueryWord, List<String> aTokens)
        {
            // Return some score
            return 1;
        }
    }
    
    public static class AggregateScoreAnnotator
        extends JCasAnnotator_ImplBase
    {
        @Override
        public void process(JCas aJCas) throws AnalysisEngineProcessException
        {
            double score = 0.0;
            int count = 0;

            Type tUnit = aJCas.getTypeSystem().getType(TYPE_NAME_UNIT);
            Feature fUnitScore = tUnit.getFeatureByBaseName(FEATURE_NAME_SCORE);
            for (AnnotationFS unit : CasUtil.select(aJCas.getCas(), tUnit)) {
                score += unit.getDoubleValue(fUnitScore);
                count++;
            }

            Type tAggregate = aJCas.getTypeSystem().getType(TYPE_NAME_AGGREGATE);
            Feature fAggregateScore = tAggregate.getFeatureByBaseName(FEATURE_NAME_SCORE);
            AnnotationFS aggregate = aJCas.getCas().createAnnotation(tAggregate, 0,
                    aJCas.getDocumentText().length());
            aggregate.setDoubleValue(fAggregateScore, score / count);
            aJCas.getCas().addFsToIndexes(aggregate);
        }
    }
}