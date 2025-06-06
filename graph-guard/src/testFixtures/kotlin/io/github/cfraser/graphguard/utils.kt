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
package io.github.cfraser.graphguard

import io.github.cfraser.graphguard.Server.Plugin.DSL.plugin
import io.github.cfraser.graphguard.knit.test
import io.kotest.assertions.fail
import io.kotest.core.NamedTag
import io.kotest.matchers.collections.shouldHaveSize
import org.neo4j.driver.AuthToken
import org.neo4j.driver.Config
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Values
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.net.URI

val LOCAL = NamedTag("Local")

/** Return whether the E2E tests should be executed. */
val isE2eTest: Boolean
  get() = System.getProperty("graph-guard.e2e.test")?.toBooleanStrictOrNull() == true

/** Use a running [Neo4j] with the test [block]. */
fun <T> withNeo4j(block: Neo4j.() -> T): T {
  return Neo4jBuilders.newInProcessBuilder().build().use { neo4j -> neo4j.block() }
}

/** Run the [block] using a [Server] initialized with the [plugin]. */
fun <T> Neo4j.withServer(plugin: Server.Plugin = plugin {}, block: (Driver) -> T): T {
  var t: T? = null
  val server = Server(boltURI(), plugin)
  server.test { server.driver.use { driver -> t = block(driver) } }
  return checkNotNull(t)
}

/** Get a [Driver] for the [Neo4j] database. */
val Neo4j.driver: Driver
  get() {
    return GraphDatabase.driver(boltURI(), Config.builder().withoutEncryption().build())
  }

/** Get a [Driver] for the proxy [Server]. */
val Server.driver: Driver
  get() {
    return GraphDatabase.driver(
        URI("bolt://${address.hostName}:${address.port}"),
        Config.builder().withoutEncryption().build())
  }

/** Get a [Driver] for the proxy [server] with the [auth]. */
fun driver(server: URI = URI("bolt://localhost:8787"), auth: AuthToken? = null): Driver {
  return if (auth == null)
      GraphDatabase.driver(server, Config.builder().withoutEncryption().build())
  else GraphDatabase.driver(server, auth, Config.builder().withoutEncryption().build())
}

/** Run [MoviesGraph] queries using the [driver]. */
fun runMoviesQueries(driver: Driver) {
  driver.session().use { session ->
    MoviesGraph.CREATE.forEach(session::run)
    session.run(MoviesGraph.MATCH_TOM_HANKS).list() shouldHaveSize 1
    session.run(MoviesGraph.MATCH_CLOUD_ATLAS).list() shouldHaveSize 1
    session.run(MoviesGraph.MATCH_10_PEOPLE).list() shouldHaveSize 10
    session.run(MoviesGraph.MATCH_NINETIES_MOVIES).list() shouldHaveSize 20
    session.run(MoviesGraph.MATCH_TOM_HANKS_MOVIES).list() shouldHaveSize 12
    session.run(MoviesGraph.MATCH_CLOUD_ATLAS_DIRECTOR).list() shouldHaveSize 3
    session.run(MoviesGraph.MATCH_TOM_HANKS_CO_ACTORS).list() shouldHaveSize 34
    session.run(MoviesGraph.MATCH_CLOUD_ATLAS_PEOPLE).list() shouldHaveSize 10
    session.run(MoviesGraph.MATCH_SIX_DEGREES_OF_KEVIN_BACON).list() shouldHaveSize 170
    session
        .run(MoviesGraph.MATCH_PATH_FROM_KEVIN_BACON_TO, Values.parameters("name", "Tom Hanks"))
        .list() shouldHaveSize 1
    session.run(MoviesGraph.MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS).list() shouldHaveSize 44
    session
        .run(
            MoviesGraph.MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND,
            Values.parameters("name", "Keanu Reeves"))
        .list() shouldHaveSize 4
    session
        .runCatching { run("invalid cypher") }
        .onSuccess { fail("Expected exception from invalid cypher") }
  }
}

/** Cypher for the [movies](https://github.com/neo4j-graph-examples/movies) graph. */
object MoviesGraph {

  val CREATE =
      """
CREATE CONSTRAINT IF NOT EXISTS FOR (p:Person) REQUIRE (p.name) IS UNIQUE;
CREATE INDEX IF NOT EXISTS FOR (p:Person) ON (p.born);
CREATE CONSTRAINT IF NOT EXISTS FOR (m:Movie) REQUIRE (m.title) IS UNIQUE;
CREATE INDEX IF NOT EXISTS FOR (m:Movie) ON (m.released);

MERGE (TheMatrix:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})
MERGE (Keanu:Person {name:'Keanu Reeves', born:1964})
MERGE (Carrie:Person {name:'Carrie-Anne Moss', born:1967})
MERGE (Laurence:Person {name:'Laurence Fishburne', born:1961})
MERGE (Hugo:Person {name:'Hugo Weaving', born:1960})
MERGE (LillyW:Person {name:'Lilly Wachowski', born:1967})
MERGE (LanaW:Person {name:'Lana Wachowski', born:1965})
MERGE (JoelS:Person {name:'Joel Silver', born:1952})
MERGE (Keanu)-[:ACTED_IN {roles:['Neo']}]->(TheMatrix)
MERGE (Carrie)-[:ACTED_IN {roles:['Trinity']}]->(TheMatrix)
MERGE (Laurence)-[:ACTED_IN {roles:['Morpheus']}]->(TheMatrix)
MERGE (Hugo)-[:ACTED_IN {roles:['Agent Smith']}]->(TheMatrix)
MERGE (LillyW)-[:DIRECTED]->(TheMatrix)
MERGE (LanaW)-[:DIRECTED]->(TheMatrix)
MERGE (JoelS)-[:PRODUCED]->(TheMatrix)

MERGE (Emil:Person {name:"Emil Eifrem", born:1978})
MERGE (Emil)-[:ACTED_IN {roles:["Emil"]}]->(TheMatrix)

MERGE (TheMatrixReloaded:Movie {title:'The Matrix Reloaded', released:2003, tagline:'Free your mind'})
MERGE (Keanu)-[:ACTED_IN {roles:['Neo']}]->(TheMatrixReloaded)
MERGE (Carrie)-[:ACTED_IN {roles:['Trinity']}]->(TheMatrixReloaded)
MERGE (Laurence)-[:ACTED_IN {roles:['Morpheus']}]->(TheMatrixReloaded)
MERGE (Hugo)-[:ACTED_IN {roles:['Agent Smith']}]->(TheMatrixReloaded)
MERGE (LillyW)-[:DIRECTED]->(TheMatrixReloaded)
MERGE (LanaW)-[:DIRECTED]->(TheMatrixReloaded)
MERGE (JoelS)-[:PRODUCED]->(TheMatrixReloaded)

MERGE (TheMatrixRevolutions:Movie {title:'The Matrix Revolutions', released:2003, tagline:'Everything that has a beginning has an end'})
MERGE (Keanu)-[:ACTED_IN {roles:['Neo']}]->(TheMatrixRevolutions)
MERGE (Carrie)-[:ACTED_IN {roles:['Trinity']}]->(TheMatrixRevolutions)
MERGE (Laurence)-[:ACTED_IN {roles:['Morpheus']}]->(TheMatrixRevolutions)
MERGE (Hugo)-[:ACTED_IN {roles:['Agent Smith']}]->(TheMatrixRevolutions)
MERGE (LillyW)-[:DIRECTED]->(TheMatrixRevolutions)
MERGE (LanaW)-[:DIRECTED]->(TheMatrixRevolutions)
MERGE (JoelS)-[:PRODUCED]->(TheMatrixRevolutions)

MERGE (TheDevilsAdvocate:Movie {title:"The Devil's Advocate", released:1997, tagline:'Evil has its winning ways'})
MERGE (Charlize:Person {name:'Charlize Theron', born:1975})
MERGE (Al:Person {name:'Al Pacino', born:1940})
MERGE (Taylor:Person {name:'Taylor Hackford', born:1944})
MERGE (Keanu)-[:ACTED_IN {roles:['Kevin Lomax']}]->(TheDevilsAdvocate)
MERGE (Charlize)-[:ACTED_IN {roles:['Mary Ann Lomax']}]->(TheDevilsAdvocate)
MERGE (Al)-[:ACTED_IN {roles:['John Milton']}]->(TheDevilsAdvocate)
MERGE (Taylor)-[:DIRECTED]->(TheDevilsAdvocate)

MERGE (AFewGoodMen:Movie {title:"A Few Good Men", released:1992, tagline:"In the heart of the nation's capital, in a courthouse of the U.S. government, one man will stop at nothing to keep his honor, and one will stop at nothing to find the truth."})
MERGE (TomC:Person {name:'Tom Cruise', born:1962})
MERGE (JackN:Person {name:'Jack Nicholson', born:1937})
MERGE (DemiM:Person {name:'Demi Moore', born:1962})
MERGE (KevinB:Person {name:'Kevin Bacon', born:1958})
MERGE (KieferS:Person {name:'Kiefer Sutherland', born:1966})
MERGE (NoahW:Person {name:'Noah Wyle', born:1971})
MERGE (CubaG:Person {name:'Cuba Gooding Jr.', born:1968})
MERGE (KevinP:Person {name:'Kevin Pollak', born:1957})
MERGE (JTW:Person {name:'J.T. Walsh', born:1943})
MERGE (JamesM:Person {name:'James Marshall', born:1967})
MERGE (ChristopherG:Person {name:'Christopher Guest', born:1948})
MERGE (RobR:Person {name:'Rob Reiner', born:1947})
MERGE (AaronS:Person {name:'Aaron Sorkin', born:1961})
MERGE (TomC)-[:ACTED_IN {roles:['Lt. Daniel Kaffee']}]->(AFewGoodMen)
MERGE (JackN)-[:ACTED_IN {roles:['Col. Nathan R. Jessup']}]->(AFewGoodMen)
MERGE (DemiM)-[:ACTED_IN {roles:['Lt. Cdr. JoAnne Galloway']}]->(AFewGoodMen)
MERGE (KevinB)-[:ACTED_IN {roles:['Capt. Jack Ross']}]->(AFewGoodMen)
MERGE (KieferS)-[:ACTED_IN {roles:['Lt. Jonathan Kendrick']}]->(AFewGoodMen)
MERGE (NoahW)-[:ACTED_IN {roles:['Cpl. Jeffrey Barnes']}]->(AFewGoodMen)
MERGE (CubaG)-[:ACTED_IN {roles:['Cpl. Carl Hammaker']}]->(AFewGoodMen)
MERGE (KevinP)-[:ACTED_IN {roles:['Lt. Sam Weinberg']}]->(AFewGoodMen)
MERGE (JTW)-[:ACTED_IN {roles:['Lt. Col. Matthew Andrew Markinson']}]->(AFewGoodMen)
MERGE (JamesM)-[:ACTED_IN {roles:['Pfc. Louden Downey']}]->(AFewGoodMen)
MERGE (ChristopherG)-[:ACTED_IN {roles:['Dr. Stone']}]->(AFewGoodMen)
MERGE (AaronS)-[:ACTED_IN {roles:['Man in Bar']}]->(AFewGoodMen)
MERGE (RobR)-[:DIRECTED]->(AFewGoodMen)
MERGE (AaronS)-[:WROTE]->(AFewGoodMen)

MERGE (TopGun:Movie {title:"Top Gun", released:1986, tagline:'I feel the need, the need for speed.'})
MERGE (KellyM:Person {name:'Kelly McGillis', born:1957})
MERGE (ValK:Person {name:'Val Kilmer', born:1959})
MERGE (AnthonyE:Person {name:'Anthony Edwards', born:1962})
MERGE (TomS:Person {name:'Tom Skerritt', born:1933})
MERGE (MegR:Person {name:'Meg Ryan', born:1961})
MERGE (TonyS:Person {name:'Tony Scott', born:1944})
MERGE (JimC:Person {name:'Jim Cash', born:1941})
MERGE (TomC)-[:ACTED_IN {roles:['Maverick']}]->(TopGun)
MERGE (KellyM)-[:ACTED_IN {roles:['Charlie']}]->(TopGun)
MERGE (ValK)-[:ACTED_IN {roles:['Iceman']}]->(TopGun)
MERGE (AnthonyE)-[:ACTED_IN {roles:['Goose']}]->(TopGun)
MERGE (TomS)-[:ACTED_IN {roles:['Viper']}]->(TopGun)
MERGE (MegR)-[:ACTED_IN {roles:['Carole']}]->(TopGun)
MERGE (TonyS)-[:DIRECTED]->(TopGun)
MERGE (JimC)-[:WROTE]->(TopGun)

MERGE (JerryMaguire:Movie {title:'Jerry Maguire', released:2000, tagline:'The rest of his life begins now.'})
MERGE (ReneeZ:Person {name:'Renee Zellweger', born:1969})
MERGE (KellyP:Person {name:'Kelly Preston', born:1962})
MERGE (JerryO:Person {name:"Jerry O'Connell", born:1974})
MERGE (JayM:Person {name:'Jay Mohr', born:1970})
MERGE (BonnieH:Person {name:'Bonnie Hunt', born:1961})
MERGE (ReginaK:Person {name:'Regina King', born:1971})
MERGE (JonathanL:Person {name:'Jonathan Lipnicki', born:1996})
MERGE (CameronC:Person {name:'Cameron Crowe', born:1957})
MERGE (TomC)-[:ACTED_IN {roles:['Jerry Maguire']}]->(JerryMaguire)
MERGE (CubaG)-[:ACTED_IN {roles:['Rod Tidwell']}]->(JerryMaguire)
MERGE (ReneeZ)-[:ACTED_IN {roles:['Dorothy Boyd']}]->(JerryMaguire)
MERGE (KellyP)-[:ACTED_IN {roles:['Avery Bishop']}]->(JerryMaguire)
MERGE (JerryO)-[:ACTED_IN {roles:['Frank Cushman']}]->(JerryMaguire)
MERGE (JayM)-[:ACTED_IN {roles:['Bob Sugar']}]->(JerryMaguire)
MERGE (BonnieH)-[:ACTED_IN {roles:['Laurel Boyd']}]->(JerryMaguire)
MERGE (ReginaK)-[:ACTED_IN {roles:['Marcee Tidwell']}]->(JerryMaguire)
MERGE (JonathanL)-[:ACTED_IN {roles:['Ray Boyd']}]->(JerryMaguire)
MERGE (CameronC)-[:DIRECTED]->(JerryMaguire)
MERGE (CameronC)-[:PRODUCED]->(JerryMaguire)
MERGE (CameronC)-[:WROTE]->(JerryMaguire)

MERGE (StandByMe:Movie {title:"Stand By Me", released:1986, tagline:"For some, it's the last real taste of innocence, and the first real taste of life. But for everyone, it's the time that memories are made of."})
MERGE (RiverP:Person {name:'River Phoenix', born:1970})
MERGE (CoreyF:Person {name:'Corey Feldman', born:1971})
MERGE (WilW:Person {name:'Wil Wheaton', born:1972})
MERGE (JohnC:Person {name:'John Cusack', born:1966})
MERGE (MarshallB:Person {name:'Marshall Bell', born:1942})
MERGE (WilW)-[:ACTED_IN {roles:['Gordie Lachance']}]->(StandByMe)
MERGE (RiverP)-[:ACTED_IN {roles:['Chris Chambers']}]->(StandByMe)
MERGE (JerryO)-[:ACTED_IN {roles:['Vern Tessio']}]->(StandByMe)
MERGE (CoreyF)-[:ACTED_IN {roles:['Teddy Duchamp']}]->(StandByMe)
MERGE (JohnC)-[:ACTED_IN {roles:['Denny Lachance']}]->(StandByMe)
MERGE (KieferS)-[:ACTED_IN {roles:['Ace Merrill']}]->(StandByMe)
MERGE (MarshallB)-[:ACTED_IN {roles:['Mr. Lachance']}]->(StandByMe)
MERGE (RobR)-[:DIRECTED]->(StandByMe)

MERGE (AsGoodAsItGets:Movie {title:'As Good as It Gets', released:1997, tagline:'A comedy from the heart that goes for the throat.'})
MERGE (HelenH:Person {name:'Helen Hunt', born:1963})
MERGE (GregK:Person {name:'Greg Kinnear', born:1963})
MERGE (JamesB:Person {name:'James L. Brooks', born:1940})
MERGE (JackN)-[:ACTED_IN {roles:['Melvin Udall']}]->(AsGoodAsItGets)
MERGE (HelenH)-[:ACTED_IN {roles:['Carol Connelly']}]->(AsGoodAsItGets)
MERGE (GregK)-[:ACTED_IN {roles:['Simon Bishop']}]->(AsGoodAsItGets)
MERGE (CubaG)-[:ACTED_IN {roles:['Frank Sachs']}]->(AsGoodAsItGets)
MERGE (JamesB)-[:DIRECTED]->(AsGoodAsItGets)

MERGE (WhatDreamsMayCome:Movie {title:'What Dreams May Come', released:1998, tagline:'After life there is more. The end is just the beginning.'})
MERGE (AnnabellaS:Person {name:'Annabella Sciorra', born:1960})
MERGE (MaxS:Person {name:'Max von Sydow', born:1929})
MERGE (WernerH:Person {name:'Werner Herzog', born:1942})
MERGE (Robin:Person {name:'Robin Williams', born:1951})
MERGE (VincentW:Person {name:'Vincent Ward', born:1956})
MERGE (Robin)-[:ACTED_IN {roles:['Chris Nielsen']}]->(WhatDreamsMayCome)
MERGE (CubaG)-[:ACTED_IN {roles:['Albert Lewis']}]->(WhatDreamsMayCome)
MERGE (AnnabellaS)-[:ACTED_IN {roles:['Annie Collins-Nielsen']}]->(WhatDreamsMayCome)
MERGE (MaxS)-[:ACTED_IN {roles:['The Tracker']}]->(WhatDreamsMayCome)
MERGE (WernerH)-[:ACTED_IN {roles:['The Face']}]->(WhatDreamsMayCome)
MERGE (VincentW)-[:DIRECTED]->(WhatDreamsMayCome)

MERGE (SnowFallingonCedars:Movie {title:'Snow Falling on Cedars', released:1999, tagline:'First loves last. Forever.'})
MERGE (EthanH:Person {name:'Ethan Hawke', born:1970})
MERGE (RickY:Person {name:'Rick Yune', born:1971})
MERGE (JamesC:Person {name:'James Cromwell', born:1940})
MERGE (ScottH:Person {name:'Scott Hicks', born:1953})
MERGE (EthanH)-[:ACTED_IN {roles:['Ishmael Chambers']}]->(SnowFallingonCedars)
MERGE (RickY)-[:ACTED_IN {roles:['Kazuo Miyamoto']}]->(SnowFallingonCedars)
MERGE (MaxS)-[:ACTED_IN {roles:['Nels Gudmundsson']}]->(SnowFallingonCedars)
MERGE (JamesC)-[:ACTED_IN {roles:['Judge Fielding']}]->(SnowFallingonCedars)
MERGE (ScottH)-[:DIRECTED]->(SnowFallingonCedars)

MERGE (YouveGotMail:Movie {title:"You've Got Mail", released:1998, tagline:'At odds in life... in love on-line.'})
MERGE (ParkerP:Person {name:'Parker Posey', born:1968})
MERGE (DaveC:Person {name:'Dave Chappelle', born:1973})
MERGE (SteveZ:Person {name:'Steve Zahn', born:1967})
MERGE (TomH:Person {name:'Tom Hanks', born:1956})
MERGE (NoraE:Person {name:'Nora Ephron', born:1941})
MERGE (TomH)-[:ACTED_IN {roles:['Joe Fox']}]->(YouveGotMail)
MERGE (MegR)-[:ACTED_IN {roles:['Kathleen Kelly']}]->(YouveGotMail)
MERGE (GregK)-[:ACTED_IN {roles:['Frank Navasky']}]->(YouveGotMail)
MERGE (ParkerP)-[:ACTED_IN {roles:['Patricia Eden']}]->(YouveGotMail)
MERGE (DaveC)-[:ACTED_IN {roles:['Kevin Jackson']}]->(YouveGotMail)
MERGE (SteveZ)-[:ACTED_IN {roles:['George Pappas']}]->(YouveGotMail)
MERGE (NoraE)-[:DIRECTED]->(YouveGotMail)

MERGE (SleeplessInSeattle:Movie {title:'Sleepless in Seattle', released:1993, tagline:'What if someone you never met, someone you never saw, someone you never knew was the only someone for you?'})
MERGE (RitaW:Person {name:'Rita Wilson', born:1956})
MERGE (BillPull:Person {name:'Bill Pullman', born:1953})
MERGE (VictorG:Person {name:'Victor Garber', born:1949})
MERGE (RosieO:Person {name:"Rosie O'Donnell", born:1962})
MERGE (TomH)-[:ACTED_IN {roles:['Sam Baldwin']}]->(SleeplessInSeattle)
MERGE (MegR)-[:ACTED_IN {roles:['Annie Reed']}]->(SleeplessInSeattle)
MERGE (RitaW)-[:ACTED_IN {roles:['Suzy']}]->(SleeplessInSeattle)
MERGE (BillPull)-[:ACTED_IN {roles:['Walter']}]->(SleeplessInSeattle)
MERGE (VictorG)-[:ACTED_IN {roles:['Greg']}]->(SleeplessInSeattle)
MERGE (RosieO)-[:ACTED_IN {roles:['Becky']}]->(SleeplessInSeattle)
MERGE (NoraE)-[:DIRECTED]->(SleeplessInSeattle)

MERGE (JoeVersustheVolcano:Movie {title:'Joe Versus the Volcano', released:1990, tagline:'A story of love, lava and burning desire.'})
MERGE (JohnS:Person {name:'John Patrick Stanley', born:1950})
MERGE (Nathan:Person {name:'Nathan Lane', born:1956})
MERGE (TomH)-[:ACTED_IN {roles:['Joe Banks']}]->(JoeVersustheVolcano)
MERGE (MegR)-[:ACTED_IN {roles:['DeDe', 'Angelica Graynamore', 'Patricia Graynamore']}]->(JoeVersustheVolcano)
MERGE (Nathan)-[:ACTED_IN {roles:['Baw']}]->(JoeVersustheVolcano)
MERGE (JohnS)-[:DIRECTED]->(JoeVersustheVolcano)

MERGE (WhenHarryMetSally:Movie {title:'When Harry Met Sally', released:1998, tagline:'Can two friends sleep together and still love each other in the morning?'})
MERGE (BillyC:Person {name:'Billy Crystal', born:1948})
MERGE (CarrieF:Person {name:'Carrie Fisher', born:1956})
MERGE (BrunoK:Person {name:'Bruno Kirby', born:1949})
MERGE (BillyC)-[:ACTED_IN {roles:['Harry Burns']}]->(WhenHarryMetSally)
MERGE (MegR)-[:ACTED_IN {roles:['Sally Albright']}]->(WhenHarryMetSally)
MERGE (CarrieF)-[:ACTED_IN {roles:['Marie']}]->(WhenHarryMetSally)
MERGE (BrunoK)-[:ACTED_IN {roles:['Jess']}]->(WhenHarryMetSally)
MERGE (RobR)-[:DIRECTED]->(WhenHarryMetSally)
MERGE (RobR)-[:PRODUCED]->(WhenHarryMetSally)
MERGE (NoraE)-[:PRODUCED]->(WhenHarryMetSally)
MERGE (NoraE)-[:WROTE]->(WhenHarryMetSally)

MERGE (ThatThingYouDo:Movie {title:'That Thing You Do', released:1996, tagline:'In every life there comes a time when that thing you dream becomes that thing you do'})
MERGE (LivT:Person {name:'Liv Tyler', born:1977})
MERGE (TomH)-[:ACTED_IN {roles:['Mr. White']}]->(ThatThingYouDo)
MERGE (LivT)-[:ACTED_IN {roles:['Faye Dolan']}]->(ThatThingYouDo)
MERGE (Charlize)-[:ACTED_IN {roles:['Tina']}]->(ThatThingYouDo)
MERGE (TomH)-[:DIRECTED]->(ThatThingYouDo)

MERGE (TheReplacements:Movie {title:'The Replacements', released:2000, tagline:'Pain heals, Chicks dig scars... Glory lasts forever'})
MERGE (Brooke:Person {name:'Brooke Langton', born:1970})
MERGE (Gene:Person {name:'Gene Hackman', born:1930})
MERGE (Orlando:Person {name:'Orlando Jones', born:1968})
MERGE (Howard:Person {name:'Howard Deutch', born:1950})
MERGE (Keanu)-[:ACTED_IN {roles:['Shane Falco']}]->(TheReplacements)
MERGE (Brooke)-[:ACTED_IN {roles:['Annabelle Farrell']}]->(TheReplacements)
MERGE (Gene)-[:ACTED_IN {roles:['Jimmy McGinty']}]->(TheReplacements)
MERGE (Orlando)-[:ACTED_IN {roles:['Clifford Franklin']}]->(TheReplacements)
MERGE (Howard)-[:DIRECTED]->(TheReplacements)

MERGE (RescueDawn:Movie {title:'RescueDawn', released:2006, tagline:"Based on the extraordinary true story of one man's fight for freedom"})
MERGE (ChristianB:Person {name:'Christian Bale', born:1974})
MERGE (ZachG:Person {name:'Zach Grenier', born:1954})
MERGE (MarshallB)-[:ACTED_IN {roles:['Admiral']}]->(RescueDawn)
MERGE (ChristianB)-[:ACTED_IN {roles:['Dieter Dengler']}]->(RescueDawn)
MERGE (ZachG)-[:ACTED_IN {roles:['Squad Leader']}]->(RescueDawn)
MERGE (SteveZ)-[:ACTED_IN {roles:['Duane']}]->(RescueDawn)
MERGE (WernerH)-[:DIRECTED]->(RescueDawn)

MERGE (TheBirdcage:Movie {title:'The Birdcage', released:1996, tagline:'Come as you are'})
MERGE (MikeN:Person {name:'Mike Nichols', born:1931})
MERGE (Robin)-[:ACTED_IN {roles:['Armand Goldman']}]->(TheBirdcage)
MERGE (Nathan)-[:ACTED_IN {roles:['Albert Goldman']}]->(TheBirdcage)
MERGE (Gene)-[:ACTED_IN {roles:['Sen. Kevin Keeley']}]->(TheBirdcage)
MERGE (MikeN)-[:DIRECTED]->(TheBirdcage)

MERGE (Unforgiven:Movie {title:'Unforgiven', released:1992, tagline:"It's a hell of a thing, killing a man"})
MERGE (RichardH:Person {name:'Richard Harris', born:1930})
MERGE (ClintE:Person {name:'Clint Eastwood', born:1930})
MERGE (RichardH)-[:ACTED_IN {roles:['English Bob']}]->(Unforgiven)
MERGE (ClintE)-[:ACTED_IN {roles:['Bill Munny']}]->(Unforgiven)
MERGE (Gene)-[:ACTED_IN {roles:['Little Bill Daggett']}]->(Unforgiven)
MERGE (ClintE)-[:DIRECTED]->(Unforgiven)

MERGE (JohnnyMnemonic:Movie {title:'Johnny Mnemonic', released:1995, tagline:'The hottest data on earth. In the coolest head in town'})
MERGE (Takeshi:Person {name:'Takeshi Kitano', born:1947})
MERGE (Dina:Person {name:'Dina Meyer', born:1968})
MERGE (IceT:Person {name:'Ice-T', born:1958})
MERGE (RobertL:Person {name:'Robert Longo', born:1953})
MERGE (Keanu)-[:ACTED_IN {roles:['Johnny Mnemonic']}]->(JohnnyMnemonic)
MERGE (Takeshi)-[:ACTED_IN {roles:['Takahashi']}]->(JohnnyMnemonic)
MERGE (Dina)-[:ACTED_IN {roles:['Jane']}]->(JohnnyMnemonic)
MERGE (IceT)-[:ACTED_IN {roles:['J-Bone']}]->(JohnnyMnemonic)
MERGE (RobertL)-[:DIRECTED]->(JohnnyMnemonic)

MERGE (CloudAtlas:Movie {title:'Cloud Atlas', released:2012, tagline:'Everything is connected'})
MERGE (HalleB:Person {name:'Halle Berry', born:1966})
MERGE (JimB:Person {name:'Jim Broadbent', born:1949})
MERGE (TomT:Person {name:'Tom Tykwer', born:1965})
MERGE (DavidMitchell:Person {name:'David Mitchell', born:1969})
MERGE (StefanArndt:Person {name:'Stefan Arndt', born:1961})
MERGE (TomH)-[:ACTED_IN {roles:['Zachry', 'Dr. Henry Goose', 'Isaac Sachs', 'Dermot Hoggins']}]->(CloudAtlas)
MERGE (Hugo)-[:ACTED_IN {roles:['Bill Smoke', 'Haskell Moore', 'Tadeusz Kesselring', 'Nurse Noakes', 'Boardman Mephi', 'Old Georgie']}]->(CloudAtlas)
MERGE (HalleB)-[:ACTED_IN {roles:['Luisa Rey', 'Jocasta Ayrs', 'Ovid', 'Meronym']}]->(CloudAtlas)
MERGE (JimB)-[:ACTED_IN {roles:['Vyvyan Ayrs', 'Captain Molyneux', 'Timothy Cavendish']}]->(CloudAtlas)
MERGE (TomT)-[:DIRECTED]->(CloudAtlas)
MERGE (LillyW)-[:DIRECTED]->(CloudAtlas)
MERGE (LanaW)-[:DIRECTED]->(CloudAtlas)
MERGE (DavidMitchell)-[:WROTE]->(CloudAtlas)
MERGE (StefanArndt)-[:PRODUCED]->(CloudAtlas)

MERGE (TheDaVinciCode:Movie {title:'The Da Vinci Code', released:2006, tagline:'Break The Codes'})
MERGE (IanM:Person {name:'Ian McKellen', born:1939})
MERGE (AudreyT:Person {name:'Audrey Tautou', born:1976})
MERGE (PaulB:Person {name:'Paul Bettany', born:1971})
MERGE (RonH:Person {name:'Ron Howard', born:1954})
MERGE (TomH)-[:ACTED_IN {roles:['Dr. Robert Langdon']}]->(TheDaVinciCode)
MERGE (IanM)-[:ACTED_IN {roles:['Sir Leight Teabing']}]->(TheDaVinciCode)
MERGE (AudreyT)-[:ACTED_IN {roles:['Sophie Neveu']}]->(TheDaVinciCode)
MERGE (PaulB)-[:ACTED_IN {roles:['Silas']}]->(TheDaVinciCode)
MERGE (RonH)-[:DIRECTED]->(TheDaVinciCode)

MERGE (VforVendetta:Movie {title:'V for Vendetta', released:2006, tagline:'Freedom! Forever!'})
MERGE (NatalieP:Person {name:'Natalie Portman', born:1981})
MERGE (StephenR:Person {name:'Stephen Rea', born:1946})
MERGE (JohnH:Person {name:'John Hurt', born:1940})
MERGE (BenM:Person {name: 'Ben Miles', born:1967})
MERGE (Hugo)-[:ACTED_IN {roles:['V']}]->(VforVendetta)
MERGE (NatalieP)-[:ACTED_IN {roles:['Evey Hammond']}]->(VforVendetta)
MERGE (StephenR)-[:ACTED_IN {roles:['Eric Finch']}]->(VforVendetta)
MERGE (JohnH)-[:ACTED_IN {roles:['High Chancellor Adam Sutler']}]->(VforVendetta)
MERGE (BenM)-[:ACTED_IN {roles:['Dascomb']}]->(VforVendetta)
MERGE (JamesM)-[:DIRECTED]->(VforVendetta)
MERGE (LillyW)-[:PRODUCED]->(VforVendetta)
MERGE (LanaW)-[:PRODUCED]->(VforVendetta)
MERGE (JoelS)-[:PRODUCED]->(VforVendetta)
MERGE (LillyW)-[:WROTE]->(VforVendetta)
MERGE (LanaW)-[:WROTE]->(VforVendetta)

MERGE (SpeedRacer:Movie {title:'Speed Racer', released:2008, tagline:'Speed has no limits'})
MERGE (EmileH:Person {name:'Emile Hirsch', born:1985})
MERGE (JohnG:Person {name:'John Goodman', born:1960})
MERGE (SusanS:Person {name:'Susan Sarandon', born:1946})
MERGE (MatthewF:Person {name:'Matthew Fox', born:1966})
MERGE (ChristinaR:Person {name:'Christina Ricci', born:1980})
MERGE (Rain:Person {name:'Rain', born:1982})
MERGE (EmileH)-[:ACTED_IN {roles:['Speed Racer']}]->(SpeedRacer)
MERGE (JohnG)-[:ACTED_IN {roles:['Pops']}]->(SpeedRacer)
MERGE (SusanS)-[:ACTED_IN {roles:['Mom']}]->(SpeedRacer)
MERGE (MatthewF)-[:ACTED_IN {roles:['Racer X']}]->(SpeedRacer)
MERGE (ChristinaR)-[:ACTED_IN {roles:['Trixie']}]->(SpeedRacer)
MERGE (Rain)-[:ACTED_IN {roles:['Taejo Togokahn']}]->(SpeedRacer)
MERGE (BenM)-[:ACTED_IN {roles:['Cass Jones']}]->(SpeedRacer)
MERGE (LillyW)-[:DIRECTED]->(SpeedRacer)
MERGE (LanaW)-[:DIRECTED]->(SpeedRacer)
MERGE (LillyW)-[:WROTE]->(SpeedRacer)
MERGE (LanaW)-[:WROTE]->(SpeedRacer)
MERGE (JoelS)-[:PRODUCED]->(SpeedRacer)

MERGE (NinjaAssassin:Movie {title:'Ninja Assassin', released:2009, tagline:'Prepare to enter a secret world of assassins'})
MERGE (NaomieH:Person {name:'Naomie Harris'})
MERGE (Rain)-[:ACTED_IN {roles:['Raizo']}]->(NinjaAssassin)
MERGE (NaomieH)-[:ACTED_IN {roles:['Mika Coretti']}]->(NinjaAssassin)
MERGE (RickY)-[:ACTED_IN {roles:['Takeshi']}]->(NinjaAssassin)
MERGE (BenM)-[:ACTED_IN {roles:['Ryan Maslow']}]->(NinjaAssassin)
MERGE (JamesM)-[:DIRECTED]->(NinjaAssassin)
MERGE (LillyW)-[:PRODUCED]->(NinjaAssassin)
MERGE (LanaW)-[:PRODUCED]->(NinjaAssassin)
MERGE (JoelS)-[:PRODUCED]->(NinjaAssassin)

MERGE (TheGreenMile:Movie {title:'The Green Mile', released:1999, tagline:"Walk a mile you'll never forget."})
MERGE (MichaelD:Person {name:'Michael Clarke Duncan', born:1957})
MERGE (DavidM:Person {name:'David Morse', born:1953})
MERGE (SamR:Person {name:'Sam Rockwell', born:1968})
MERGE (GaryS:Person {name:'Gary Sinise', born:1955})
MERGE (PatriciaC:Person {name:'Patricia Clarkson', born:1959})
MERGE (FrankD:Person {name:'Frank Darabont', born:1959})
MERGE (TomH)-[:ACTED_IN {roles:['Paul Edgecomb']}]->(TheGreenMile)
MERGE (MichaelD)-[:ACTED_IN {roles:['John Coffey']}]->(TheGreenMile)
MERGE (DavidM)-[:ACTED_IN {roles:['Brutus "Brutal" Howell']}]->(TheGreenMile)
MERGE (BonnieH)-[:ACTED_IN {roles:['Jan Edgecomb']}]->(TheGreenMile)
MERGE (JamesC)-[:ACTED_IN {roles:['Warden Hal Moores']}]->(TheGreenMile)
MERGE (SamR)-[:ACTED_IN {roles:['"Wild Bill" Wharton']}]->(TheGreenMile)
MERGE (GaryS)-[:ACTED_IN {roles:['Burt Hammersmith']}]->(TheGreenMile)
MERGE (PatriciaC)-[:ACTED_IN {roles:['Melinda Moores']}]->(TheGreenMile)
MERGE (FrankD)-[:DIRECTED]->(TheGreenMile)

MERGE (FrostNixon:Movie {title:'Frost/Nixon', released:2008, tagline:'400 million people were waiting for the truth.'})
MERGE (FrankL:Person {name:'Frank Langella', born:1938})
MERGE (MichaelS:Person {name:'Michael Sheen', born:1969})
MERGE (OliverP:Person {name:'Oliver Platt', born:1960})
MERGE (FrankL)-[:ACTED_IN {roles:['Richard Nixon']}]->(FrostNixon)
MERGE (MichaelS)-[:ACTED_IN {roles:['David Frost']}]->(FrostNixon)
MERGE (KevinB)-[:ACTED_IN {roles:['Jack Brennan']}]->(FrostNixon)
MERGE (OliverP)-[:ACTED_IN {roles:['Bob Zelnick']}]->(FrostNixon)
MERGE (SamR)-[:ACTED_IN {roles:['James Reston, Jr.']}]->(FrostNixon)
MERGE (RonH)-[:DIRECTED]->(FrostNixon)

MERGE (Hoffa:Movie {title:'Hoffa', released:1992, tagline:"He didn't want law. He wanted justice."})
MERGE (DannyD:Person {name:'Danny DeVito', born:1944})
MERGE (JohnR:Person {name:'John C. Reilly', born:1965})
MERGE (JackN)-[:ACTED_IN {roles:['Hoffa']}]->(Hoffa)
MERGE (DannyD)-[:ACTED_IN {roles:['Robert "Bobby" Ciaro']}]->(Hoffa)
MERGE (JTW)-[:ACTED_IN {roles:['Frank Fitzsimmons']}]->(Hoffa)
MERGE (JohnR)-[:ACTED_IN {roles:['Peter "Pete" Connelly']}]->(Hoffa)
MERGE (DannyD)-[:DIRECTED]->(Hoffa)

MERGE (Apollo13:Movie {title:'Apollo 13', released:1995, tagline:'Houston, we have a problem.'})
MERGE (EdH:Person {name:'Ed Harris', born:1950})
MERGE (BillPax:Person {name:'Bill Paxton', born:1955})
MERGE (TomH)-[:ACTED_IN {roles:['Jim Lovell']}]->(Apollo13)
MERGE (KevinB)-[:ACTED_IN {roles:['Jack Swigert']}]->(Apollo13)
MERGE (EdH)-[:ACTED_IN {roles:['Gene Kranz']}]->(Apollo13)
MERGE (BillPax)-[:ACTED_IN {roles:['Fred Haise']}]->(Apollo13)
MERGE (GaryS)-[:ACTED_IN {roles:['Ken Mattingly']}]->(Apollo13)
MERGE (RonH)-[:DIRECTED]->(Apollo13)

MERGE (Twister:Movie {title:'Twister', released:1996, tagline:"Don't Breathe. Don't Look Back."})
MERGE (PhilipH:Person {name:'Philip Seymour Hoffman', born:1967})
MERGE (JanB:Person {name:'Jan de Bont', born:1943})
MERGE (BillPax)-[:ACTED_IN {roles:['Bill Harding']}]->(Twister)
MERGE (HelenH)-[:ACTED_IN {roles:['Dr. Jo Harding']}]->(Twister)
MERGE (ZachG)-[:ACTED_IN {roles:['Eddie']}]->(Twister)
MERGE (PhilipH)-[:ACTED_IN {roles:['Dustin "Dusty" Davis']}]->(Twister)
MERGE (JanB)-[:DIRECTED]->(Twister)

MERGE (CastAway:Movie {title:'Cast Away', released:2000, tagline:'At the edge of the world, his journey begins.'})
MERGE (RobertZ:Person {name:'Robert Zemeckis', born:1951})
MERGE (TomH)-[:ACTED_IN {roles:['Chuck Noland']}]->(CastAway)
MERGE (HelenH)-[:ACTED_IN {roles:['Kelly Frears']}]->(CastAway)
MERGE (RobertZ)-[:DIRECTED]->(CastAway)

MERGE (OneFlewOvertheCuckoosNest:Movie {title:"One Flew Over the Cuckoo's Nest", released:1975, tagline:"If he's crazy, what does that make you?"})
MERGE (MilosF:Person {name:'Milos Forman', born:1932})
MERGE (JackN)-[:ACTED_IN {roles:['Randle McMurphy']}]->(OneFlewOvertheCuckoosNest)
MERGE (DannyD)-[:ACTED_IN {roles:['Martini']}]->(OneFlewOvertheCuckoosNest)
MERGE (MilosF)-[:DIRECTED]->(OneFlewOvertheCuckoosNest)

MERGE (SomethingsGottaGive:Movie {title:"Something's Gotta Give", released:2003})
MERGE (DianeK:Person {name:'Diane Keaton', born:1946})
MERGE (NancyM:Person {name:'Nancy Meyers', born:1949})
MERGE (JackN)-[:ACTED_IN {roles:['Harry Sanborn']}]->(SomethingsGottaGive)
MERGE (DianeK)-[:ACTED_IN {roles:['Erica Barry']}]->(SomethingsGottaGive)
MERGE (Keanu)-[:ACTED_IN {roles:['Julian Mercer']}]->(SomethingsGottaGive)
MERGE (NancyM)-[:DIRECTED]->(SomethingsGottaGive)
MERGE (NancyM)-[:PRODUCED]->(SomethingsGottaGive)
MERGE (NancyM)-[:WROTE]->(SomethingsGottaGive)

MERGE (BicentennialMan:Movie {title:'Bicentennial Man', released:1999, tagline:"One robot's 200 year journey to become an ordinary man."})
MERGE (ChrisC:Person {name:'Chris Columbus', born:1958})
MERGE (Robin)-[:ACTED_IN {roles:['Andrew Marin']}]->(BicentennialMan)
MERGE (OliverP)-[:ACTED_IN {roles:['Rupert Burns']}]->(BicentennialMan)
MERGE (ChrisC)-[:DIRECTED]->(BicentennialMan)

MERGE (CharlieWilsonsWar:Movie {title:"Charlie Wilson's War", released:2007, tagline:"A stiff drink. A little mascara. A lot of nerve. Who said they couldn't bring down the Soviet empire."})
MERGE (JuliaR:Person {name:'Julia Roberts', born:1967})
MERGE (TomH)-[:ACTED_IN {roles:['Rep. Charlie Wilson']}]->(CharlieWilsonsWar)
MERGE (JuliaR)-[:ACTED_IN {roles:['Joanne Herring']}]->(CharlieWilsonsWar)
MERGE (PhilipH)-[:ACTED_IN {roles:['Gust Avrakotos']}]->(CharlieWilsonsWar)
MERGE (MikeN)-[:DIRECTED]->(CharlieWilsonsWar)

MERGE (ThePolarExpress:Movie {title:'The Polar Express', released:2004, tagline:'This Holiday Season… Believe'})
MERGE (TomH)-[:ACTED_IN {roles:['Hero Boy', 'Father', 'Conductor', 'Hobo', 'Scrooge', 'Santa Claus']}]->(ThePolarExpress)
MERGE (RobertZ)-[:DIRECTED]->(ThePolarExpress)

MERGE (ALeagueofTheirOwn:Movie {title:'A League of Their Own', released:1992, tagline:'Once in a lifetime you get a chance to do something different.'})
MERGE (Madonna:Person {name:'Madonna', born:1954})
MERGE (GeenaD:Person {name:'Geena Davis', born:1956})
MERGE (LoriP:Person {name:'Lori Petty', born:1963})
MERGE (PennyM:Person {name:'Penny Marshall', born:1943})
MERGE (TomH)-[:ACTED_IN {roles:['Jimmy Dugan']}]->(ALeagueofTheirOwn)
MERGE (GeenaD)-[:ACTED_IN {roles:['Dottie Hinson']}]->(ALeagueofTheirOwn)
MERGE (LoriP)-[:ACTED_IN {roles:['Kit Keller']}]->(ALeagueofTheirOwn)
MERGE (RosieO)-[:ACTED_IN {roles:['Doris Murphy']}]->(ALeagueofTheirOwn)
MERGE (Madonna)-[:ACTED_IN {roles:['"All the Way" Mae Mordabito']}]->(ALeagueofTheirOwn)
MERGE (BillPax)-[:ACTED_IN {roles:['Bob Hinson']}]->(ALeagueofTheirOwn)
MERGE (PennyM)-[:DIRECTED]->(ALeagueofTheirOwn)

MERGE (PaulBlythe:Person {name:'Paul Blythe'})
MERGE (AngelaScope:Person {name:'Angela Scope'})
MERGE (JessicaThompson:Person {name:'Jessica Thompson'})
MERGE (JamesThompson:Person {name:'James Thompson'})

MERGE (JessicaThompson)-[:REVIEWED {summary:'An amazing journey', rating:95}]->(CloudAtlas)
MERGE (JessicaThompson)-[:REVIEWED {summary:'Silly, but fun', rating:65}]->(TheReplacements)
MERGE (JamesThompson)-[:REVIEWED {summary:'The coolest football movie ever', rating:100}]->(TheReplacements)
MERGE (AngelaScope)-[:REVIEWED {summary:'Pretty funny at times', rating:62}]->(TheReplacements)
MERGE (JessicaThompson)-[:REVIEWED {summary:'Dark, but compelling', rating:85}]->(Unforgiven)
MERGE (JessicaThompson)-[:REVIEWED {summary:"Slapstick redeemed only by the Robin Williams and Gene Hackman's stellar performances", rating:45}]->(TheBirdcage)
MERGE (JessicaThompson)-[:REVIEWED {summary:'A solid romp', rating:68}]->(TheDaVinciCode)
MERGE (JamesThompson)-[:REVIEWED {summary:'Fun, but a little far fetched', rating:65}]->(TheDaVinciCode)
MERGE (JessicaThompson)-[:REVIEWED {summary:'You had me at Jerry', rating:92}]->(JerryMaguire);"""
          .split(";")
          .map(String::trim)
          .filterNot(String::isEmpty)

  const val MATCH_TOM_HANKS =
      """
MATCH (tom:Person {name: 'Tom Hanks'})
RETURN tom
"""

  const val MATCH_CLOUD_ATLAS =
      """
MATCH (cloudAtlas:Movie {title: 'Cloud Atlas'})
RETURN cloudAtlas
"""

  const val MATCH_10_PEOPLE =
      """
MATCH (people:Person)
RETURN people.name
  LIMIT 10
"""

  const val MATCH_NINETIES_MOVIES =
      """
MATCH (nineties:Movie)
  WHERE nineties.released >= 1990 AND nineties.released < 2000
RETURN nineties.title
"""

  const val MATCH_TOM_HANKS_MOVIES =
      """
MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(tomHanksMovies:Movie)
RETURN tom, tomHanksMovies
"""

  const val MATCH_CLOUD_ATLAS_DIRECTOR =
      """
MATCH (cloudAtlas:Movie {title: 'Cloud Atlas'})<-[:DIRECTED]-(directors:Person)
RETURN directors.name
"""

  const val MATCH_TOM_HANKS_CO_ACTORS =
      """
MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(:Movie)<-[:ACTED_IN]-(coActors:Person)
RETURN DISTINCT coActors.name
"""

  const val MATCH_CLOUD_ATLAS_PEOPLE =
      """
MATCH (people:Person)-[relatedTo]-(:Movie {title: 'Cloud Atlas'})
RETURN people.name, type(relatedTo), relatedTo.roles
"""

  const val MATCH_SIX_DEGREES_OF_KEVIN_BACON =
      """
MATCH (bacon:Person {name: 'Kevin Bacon'})-[*1..6]-(hollywood)
RETURN DISTINCT hollywood
"""

  const val MATCH_PATH_FROM_KEVIN_BACON_TO =
      """
MATCH p = shortestPath((bacon:Person {name: 'Kevin Bacon'})-[*]-(:Person {name: ${'$'}name}))
RETURN p
"""

  const val MATCH_RECOMMENDED_TOM_HANKS_CO_ACTORS =
      """
MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m)<-[:ACTED_IN]-(coActors),
      (coActors)-[:ACTED_IN]->(m2)<-[:ACTED_IN]-(cocoActors)
  WHERE NOT (tom)-[:ACTED_IN]->()<-[:ACTED_IN]-(cocoActors) AND tom <> cocoActors
RETURN cocoActors.name AS Recommended, COUNT(*) AS Strength
  ORDER BY Strength DESC
"""

  const val MATCH_CO_ACTORS_BETWEEN_TOM_HANKS_AND =
      """
MATCH (tom:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m)<-[:ACTED_IN]-(coActors),
      (coActors)-[:ACTED_IN]->(m2)<-[:ACTED_IN]-(p:Person {name: ${'$'}name})
RETURN tom, m, coActors, m2, p
"""

  const val MERGE_KEANU =
      """
MERGE (Keanu:Person {name:'Keanu Reeves'})-[:ACTED_IN {roles:['Neo']}]->(:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})
SET Keanu += ${"$"}properties
"""

  const val CREATE_MATRIX_SHOWING =
      """
MERGE (TheMatrix:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})
MERGE (AMC:Theater {name: 'AMC'})
MERGE (AMC)-[Showing:SHOWING]->(TheMatrix)
SET Showing += ${"$"}properties
"""
}
