/******************************************************************************
* Copyright 2013-2016 LASIGE                                                  *
*                                                                             *
* Licensed under the Apache License, Version 2.0 (the "License"); you may     *
* not use this file except in compliance with the License. You may obtain a   *
* copy of the License at http://www.apache.org/licenses/LICENSE-2.0           *
*                                                                             *
* Unless required by applicable law or agreed to in writing, software         *
* distributed under the License is distributed on an "AS IS" BASIS,           *
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    *
* See the License for the specific language governing permissions and         *
* limitations under the License.                                              *
*                                                                             *
*******************************************************************************
* Automatic AgreementMakerLight decision & matching system (as used in OAEI). *
*                                                                             *
* @author Daniel Faria                                                        *
******************************************************************************/
package aml.match;

import java.io.IOException;
import java.util.Vector;

import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import aml.AML;
import aml.filter.DomainAndRangeFilterer;
import aml.filter.InteractiveFilterer;
import aml.filter.ObsoleteFilterer;
import aml.filter.Repairer;
import aml.filter.Selector;
import aml.knowledge.MediatorLexicon;
import aml.ontology.Ontology;
import aml.settings.EntityType;
import aml.settings.LanguageSetting;
import aml.settings.NeighborSimilarityStrategy;
import aml.settings.SelectionType;
import aml.settings.SizeCategory;
import aml.util.InteractionManager;

public class AutomaticMatcher
{
	
//Attributes

	//Link to the AML class and ontologies
	private static AML aml;
	private static Ontology source,target;
	//Interaction manager
	private static InteractionManager im;
	//Settings
	private static boolean matchClasses, matchIndividuals, matchProperties;
	private static SizeCategory size;
	//BackgroundKnowledge path
	private static final String BK_PATH = "store/knowledge/";
	//Thresholds
	private static double thresh;
	private static double psmThresh;
	private static double wnThresh;
	private static final double BASE_THRESH = 0.6;
	private static final double HIGH_GAIN_THRESH = 0.25;
	private static final double MIN_GAIN_THRESH = 0.02;
	//And their modifiers
	private static final double INTER_MOD = -0.3;
	private static final double TRANS_MOD = -0.15;
	private static final double SIZE_MOD = 0.1;
	//Alignments
	private static Alignment a;
	private static Alignment lex;
	
//Constructors	
	
	private AutomaticMatcher(){}
	
//Public Methods

	public static void match() throws UnsupportedEntityTypeException
	{
		//Get the AML instance
		aml = AML.getInstance();
		source = aml.getSource();
		target = aml.getTarget();
		//The interaction manager
		im = new InteractionManager();
		//What entity types to match
		matchClasses = aml.matchClasses();
		matchIndividuals = aml.matchIndividuals();
		matchProperties = aml.matchProperties();
		size = aml.getSizeCategory();
		//Initialize the alignment
		a = new Alignment();

    	thresh = BASE_THRESH;
		psmThresh = 0.7;
		wnThresh = 0.1;
    	if(im.isInteractive())
    	{
    		thresh += INTER_MOD;
			wnThresh = 0.04;
    	}		
		//If translation is necessary, translate
		if(LanguageSetting.getLanguageSetting().equals(LanguageSetting.TRANSLATE))
		{
			aml.translateOntologies();
    		thresh += TRANS_MOD;
			psmThresh = thresh;
		}
		if(matchClasses)
			matchClasses();
		if(matchProperties)
			matchProperties();
		if(matchIndividuals)
			matchIndividuals();

		//Perform selection and repair
		selection();
		repair();
	}
		
//Private Methods

	private static void matchClasses() throws UnsupportedEntityTypeException
	{
		LexicalMatcher lm = new LexicalMatcher();
		lex = lm.match(EntityType.CLASS, thresh);
		a.addAll(lex);
    	if(size.equals(SizeCategory.HUGE))
    		thresh += SIZE_MOD;
		LanguageSetting lang = LanguageSetting.getLanguageSetting();
		
		if(lang.equals(LanguageSetting.SINGLE))
		{
			if(size.equals(SizeCategory.SMALL))
			{
				WordNetMatcher wn = new WordNetMatcher();
				Alignment wordNet = wn.match(EntityType.CLASS, thresh);
				//Deciding whether to use it based on its coverage of the input ontologies
				//(as we expect a high gain if the coverage is high given that WordNet will
				//generate numerous synonyms)
				double coverage = Math.min(wordNet.sourceCoverage(),wordNet.targetCoverage());
				if(coverage >= wnThresh)
					a.addAllOneToOne(wordNet);		
			}
			else
			{
				Vector<String> bkSources = new Vector<String>();
				bkSources.addAll(aml.getBKSources());
				for(String bk : bkSources)
				{
					//In the case of BK Lexicons and Ontologies, we decide whether to use them
					//based on their mapping gain (over the direct Lexical alignment)
					if(bk.endsWith(".lexicon"))
					{
						try
						{
							MediatorLexicon ml = new MediatorLexicon(BK_PATH + bk);
							MediatingMatcher mm = new MediatingMatcher(ml, BK_PATH + bk);
							Alignment med = mm.match(EntityType.CLASS, thresh);
							double gain = med.gain(lex);
							if(gain >= MIN_GAIN_THRESH)
								a.addAll(med);
						}
						catch(IOException e)
						{
							System.out.println("WARNING: Could not open lexicon " + bk);
							e.printStackTrace();
							continue;						
						}
					}
					else
					{
						try
						{
							aml.openBKOntology(bk);
						}
						catch(OWLOntologyCreationException e)
						{
							System.out.println("WARNING: Could not open ontology " + bk);
							System.out.println(e.getMessage());
							continue;
						}
						XRefMatcher xr = new XRefMatcher(aml.getBKOntology());
						Alignment ref = xr.match(EntityType.CLASS, thresh);
						double gain = ref.gain(lex);
						//In the case of Ontologies, if the mapping gain is very high, we can
						//use them for Lexical Extension, which will effectively enable Word-
						//and String-Matching with the BK Ontologies' names
						if(gain >= HIGH_GAIN_THRESH)
						{
							xr.extendLexicons();
							//If that is the case, we must compute a new Lexical alignment
							//after the extension
							a.addAll(lm.match(EntityType.CLASS, thresh));
						}
						//Otherwise, we add the BK alignment as normal
						else if(gain >= MIN_GAIN_THRESH)
							a.addAll(ref);					
					}
				}
			}
		}
		if(!size.equals(SizeCategory.HUGE))
		{
			Alignment word = new Alignment();
			if(lang.equals(LanguageSetting.SINGLE))
			{
				WordMatcher wm = new WordMatcher();
				word.addAll(wm.match(EntityType.CLASS, thresh));
			}
			else if(lang.equals(LanguageSetting.MULTI))
			{
				for(String l : aml.getLanguages())
				{
					WordMatcher wm = new WordMatcher(l);
					word.addAll(wm.match(EntityType.CLASS, thresh));
				}
			}
			a.addAllOneToOne(word);
		}
		StringMatcher psm = new StringMatcher();
		//If the task is small, we can use the PSM in match mode
		if(aml.primaryStringMatcher())
		{
			a.addAllOneToOne(psm.match(EntityType.CLASS, psmThresh));
			//And if the task is single-language we can use the
			//MultiWordMatcher as well (which uses WordNet)
			if(size.equals(SizeCategory.SMALL) && lang.equals(LanguageSetting.SINGLE))
			{
				MultiWordMatcher mwm = new MultiWordMatcher();
				a.addAllOneToOne(mwm.match(EntityType.CLASS, thresh));
				AcronymMatcher am = new AcronymMatcher();
				a.addAllOneToOne(am.match(EntityType.CLASS, thresh));
			}
		}
		//Otherwise we use it in extendAlignment mode
		else
			a.addAllOneToOne(psm.extendAlignment(a,EntityType.CLASS,thresh));

		if(!size.equals(SizeCategory.HUGE))
		{
			double nameRatio = Math.max(1.0*source.getLexicon().nameCount(EntityType.CLASS)/source.count(EntityType.CLASS),
					1.0*target.getLexicon().nameCount(EntityType.CLASS)/target.count(EntityType.CLASS));
			System.out.println(nameRatio);
			if(nameRatio >= 1.2)
			{
				ThesaurusMatcher tm = new ThesaurusMatcher();
				a.addAllOneToOne(tm.match(EntityType.CLASS, thresh));
			}
		}
		if(size.equals(SizeCategory.SMALL) || size.equals(SizeCategory.MEDIUM))
		{
			NeighborSimilarityMatcher nsm = new NeighborSimilarityMatcher(
					aml.getNeighborSimilarityStrategy(),aml.directNeighbors());
			a.addAllOneToOne(nsm.extendAlignment(a,EntityType.CLASS,thresh));
		}
		aml.setAlignment(a);
	}
	
	private static void matchProperties() throws UnsupportedEntityTypeException
	{
		HybridStringMatcher pm = new HybridStringMatcher(true);
		a.addAll(pm.match(EntityType.DATA, thresh));
		a.addAll(pm.match(EntityType.OBJECT, thresh));
		aml.setAlignment(a);
		DomainAndRangeFilterer dr = new DomainAndRangeFilterer();
		dr.filter();
	}

	private static void matchIndividuals() throws UnsupportedEntityTypeException
	{
		
	}

	//Step 9 - Selection
	private static void selection() throws UnsupportedEntityTypeException
	{
		SelectionType sType = aml.getSelectionType();
		if(size.equals(SizeCategory.HUGE))
		{
			ObsoleteFilterer or = new ObsoleteFilterer();
			or.filter();
				
			BlockRematcher hl = new BlockRematcher();
			Alignment b = hl.rematch(a,EntityType.CLASS);
			NeighborSimilarityMatcher nb = new NeighborSimilarityMatcher(
					NeighborSimilarityStrategy.MAXIMUM,true);
			Alignment c = nb.rematch(a,EntityType.CLASS);
			b = LWC.combine(b, c, 0.75);
			b = LWC.combine(a, b, 0.8);
			Selector s = new Selector(thresh-0.05,sType);
			b = s.filter(b);
			s = new Selector(thresh, sType, b);
			s.filter();
		}
		else if(!im.isInteractive())
		{
			Selector s = new Selector(thresh,sType);
			s.filter();
		}
		if(im.isInteractive())
		{
			if(size.equals(SizeCategory.SMALL))
				im.setLimit((int)Math.round(a.size()*0.45));
			else
				im.setLimit((int)Math.round(a.size()*0.15));
			InteractiveFilterer in = new InteractiveFilterer();
			in.filter();
		}
	}
	
	//Step 10 - Repair
	private static void repair()
	{
		if(im.isInteractive())
			im.setLimit((int)Math.round(a.size()*0.05));
		else
			im.setLimit(0);
		Repairer r = new Repairer();
		r.filter();
	}
}