package com.nuvio.app.features.livetv.sports

import kotlin.concurrent.Volatile

/**
 * Every league and sport the Sports page knows. ESPN's free feed covers most of them (scores,
 * logos, team colors, US networks); sports it doesn't cover are found in your own TV guide.
 */
object SportsCatalog {

    private val US = setOf("US")
    private val UK = setOf("UK", "IE")
    private val NA = setOf("US", "CA")
    private val EVERYWHERE = League.ALL_REGIONS

    val leagues: List<League> = listOf(
        // ---- American football
        // NFL and NBA: on by default in the US and Canada only (elsewhere, add them in Leagues or follow a team).
        League("football/nfl", Sport.AMERICAN_FOOTBALL, "NFL", "NFL", "football/nfl", guideWords = listOf("NFL"),
            regions = NA, minutes = 195, color = 0xFF013369),
        League("football/college-football", Sport.AMERICAN_FOOTBALL, "College Football", "NCAAF", "football/college-football",
            guideWords = listOf("College Football", "NCAA Football", "NCAAF", "CFB"), regions = NA, minutes = 210,
            schoolNames = true, espnQuery = "&groups=80", color = 0xFF1E3A5F),
        // ---- Basketball
        League("basketball/nba", Sport.BASKETBALL, "NBA", "NBA", "basketball/nba", guideWords = listOf("NBA"),
            regions = NA, minutes = 150, color = 0xFF1D428A),
        League("basketball/wnba", Sport.BASKETBALL, "WNBA", "WNBA", "basketball/wnba", guideWords = listOf("WNBA"),
            regions = NA, minutes = 120, color = 0xFFC8102E),
        League("basketball/mens-college-basketball", Sport.BASKETBALL, "Men's College Basketball", "NCAAM",
            "basketball/mens-college-basketball", guideWords = listOf("College Basketball", "NCAA Basketball", "NCAAM"),
            regions = US, minutes = 125, schoolNames = true, espnQuery = "&groups=50", color = 0xFF2B4C7E),
        League("basketball/womens-college-basketball", Sport.BASKETBALL, "Women's College Basketball", "NCAAW",
            "basketball/womens-college-basketball", guideWords = listOf("Women's College Basketball", "NCAAW"),
            regions = US, minutes = 125, schoolNames = true, espnQuery = "&groups=50", color = 0xFF5B3A8C),
        // ---- Hockey and baseball
        League("hockey/nhl", Sport.HOCKEY, "NHL", "NHL", "hockey/nhl", guideWords = listOf("NHL"),
            regions = setOf("US", "CA", "OTHER"), minutes = 160, color = 0xFF111111),
        League("baseball/mlb", Sport.BASEBALL, "MLB", "MLB", "baseball/mlb", guideWords = listOf("MLB", "Baseball"),
            regions = setOf("US", "CA", "OTHER"), minutes = 185, color = 0xFF002D72),
        // ---- Football (soccer)
        League("soccer/eng.1", Sport.FOOTBALL, "Premier League", "EPL", "soccer/eng.1", guideWords = listOf("Premier League", "EPL"),
            regions = EVERYWHERE, minutes = 115, color = 0xFF3D195B),
        League("soccer/eng.2", Sport.FOOTBALL, "Championship", "EFL", "soccer/eng.2", guideWords = listOf("Championship", "EFL"),
            regions = UK, minutes = 115, color = 0xFF1C2C5B),
        League("soccer/eng.3", Sport.FOOTBALL, "League One", "L1", "soccer/eng.3", guideWords = listOf("League One", "EFL"),
            regions = UK, minutes = 115, color = 0xFF1C2C5B),
        League("soccer/eng.4", Sport.FOOTBALL, "League Two", "L2", "soccer/eng.4", guideWords = listOf("League Two", "EFL"),
            regions = UK, minutes = 115, color = 0xFF1C2C5B),
        League("soccer/eng.fa", Sport.FOOTBALL, "FA Cup", "FA CUP", "soccer/eng.fa", guideWords = listOf("FA Cup"),
            regions = UK, minutes = 125, color = 0xFF7A0019),
        League("soccer/eng.league_cup", Sport.FOOTBALL, "Carabao Cup", "EFL CUP", "soccer/eng.league_cup",
            guideWords = listOf("Carabao Cup", "EFL Cup", "League Cup"), regions = UK, minutes = 125, color = 0xFF00563F),
        League("soccer/sco.1", Sport.FOOTBALL, "Scottish Premiership", "SPFL", "soccer/sco.1",
            guideWords = listOf("Scottish Premiership", "SPFL"), regions = UK, minutes = 115, color = 0xFF2B2D7A),
        League("soccer/eng.w.1", Sport.FOOTBALL, "Women's Super League", "WSL", "soccer/eng.w.1",
            guideWords = listOf("Women's Super League", "WSL"), regions = UK, minutes = 115, color = 0xFF2C1E5A),
        League("soccer/uefa.champions", Sport.FOOTBALL, "Champions League", "UCL", "soccer/uefa.champions",
            guideWords = listOf("Champions League", "UCL"), regions = EVERYWHERE, minutes = 120, color = 0xFF0B1E5B),
        League("soccer/uefa.europa", Sport.FOOTBALL, "Europa League", "UEL", "soccer/uefa.europa",
            guideWords = listOf("Europa League", "UEL"), regions = setOf("UK", "IE", "US", "OTHER"), minutes = 120, color = 0xFFF26522),
        League("soccer/uefa.europa.conf", Sport.FOOTBALL, "Conference League", "UECL", "soccer/uefa.europa.conf",
            guideWords = listOf("Conference League", "UECL"), regions = UK, minutes = 120, color = 0xFF00A859),
        League("soccer/esp.1", Sport.FOOTBALL, "LaLiga", "LALIGA", "soccer/esp.1", guideWords = listOf("LaLiga", "La Liga"),
            regions = EVERYWHERE, minutes = 115, color = 0xFFEE3524),
        League("soccer/ita.1", Sport.FOOTBALL, "Serie A", "SERIE A", "soccer/ita.1", guideWords = listOf("Serie A"),
            regions = setOf("UK", "IE", "US", "AU", "OTHER"), minutes = 115, color = 0xFF024494),
        League("soccer/ger.1", Sport.FOOTBALL, "Bundesliga", "BUNDES", "soccer/ger.1", guideWords = listOf("Bundesliga"),
            regions = setOf("UK", "IE", "US", "AU", "OTHER"), minutes = 115, color = 0xFFD20515),
        League("soccer/fra.1", Sport.FOOTBALL, "Ligue 1", "LIGUE 1", "soccer/fra.1", guideWords = listOf("Ligue 1"),
            regions = setOf("UK", "IE", "OTHER"), minutes = 115, color = 0xFF091C3E),
        League("soccer/usa.1", Sport.FOOTBALL, "MLS", "MLS", "soccer/usa.1", guideWords = listOf("MLS"),
            regions = NA, minutes = 115, color = 0xFF001F5B),
        League("soccer/usa.nwsl", Sport.FOOTBALL, "NWSL", "NWSL", "soccer/usa.nwsl", guideWords = listOf("NWSL"),
            regions = US, minutes = 115, color = 0xFF0A2240),
        League("soccer/mex.1", Sport.FOOTBALL, "Liga MX", "LIGA MX", "soccer/mex.1", guideWords = listOf("Liga MX"),
            regions = US, minutes = 115, color = 0xFF1A7F3C),
        // ---- International (national teams: following one covers all of these)
        League("soccer/fifa.world", Sport.FOOTBALL, "World Cup", "WORLD CUP", "soccer/fifa.world",
            guideWords = listOf("World Cup", "FIFA World Cup"), regions = EVERYWHERE, minutes = 120, color = 0xFF56042C, national = true),
        League("soccer/uefa.euro", Sport.FOOTBALL, "European Championship", "EURO", "soccer/uefa.euro",
            guideWords = listOf("European Championship", "UEFA Euro"), regions = setOf("UK", "IE", "OTHER"), minutes = 120, color = 0xFF143CDB, national = true),
        League("soccer/uefa.nations", Sport.FOOTBALL, "Nations League", "UNL", "soccer/uefa.nations",
            guideWords = listOf("Nations League"), regions = setOf("UK", "IE", "OTHER"), minutes = 120, color = 0xFF0E1B4D, national = true),
        League("soccer/fifa.worldq.uefa", Sport.FOOTBALL, "World Cup Qualifying (Europe)", "WCQ", "soccer/fifa.worldq.uefa",
            guideWords = listOf("World Cup Qualifier", "World Cup Qualifying"), regions = setOf("UK", "IE", "OTHER"), minutes = 120, color = 0xFF56042C, national = true),
        League("soccer/uefa.euroq", Sport.FOOTBALL, "Euro Qualifying", "EURO Q", "soccer/uefa.euroq",
            guideWords = listOf("Euro Qualifier", "European Qualifier"), regions = setOf("UK", "IE", "OTHER"), minutes = 120, color = 0xFF143CDB, national = true),
        League("soccer/fifa.friendly", Sport.FOOTBALL, "International Friendlies", "FRIENDLY", "soccer/fifa.friendly",
            guideWords = listOf("International Friendly"), regions = emptySet(), minutes = 120, color = 0xFF33415C, national = true),
        League("soccer/fifa.worldq.concacaf", Sport.FOOTBALL, "World Cup Qualifying (CONCACAF)", "WCQ", "soccer/fifa.worldq.concacaf",
            guideWords = listOf("World Cup Qualifier", "World Cup Qualifying"), regions = NA, minutes = 120, color = 0xFF56042C, national = true),
        League("soccer/concacaf.nations.league", Sport.FOOTBALL, "CONCACAF Nations League", "CNL", "soccer/concacaf.nations.league",
            guideWords = listOf("Nations League"), regions = NA, minutes = 120, color = 0xFF002F6C, national = true),
        League("soccer/concacaf.gold", Sport.FOOTBALL, "Gold Cup", "GOLD CUP", "soccer/concacaf.gold",
            guideWords = listOf("Gold Cup"), regions = NA, minutes = 120, color = 0xFFB8860B, national = true),
        League("soccer/conmebol.america", Sport.FOOTBALL, "Copa América", "COPA", "soccer/conmebol.america",
            guideWords = listOf("Copa America"), regions = setOf("US", "OTHER"), minutes = 120, color = 0xFF003DA5, national = true),
        // ---- Rugby union internationals (ESPN; national teams: following England covers all of these)
        League("rugby/180659", Sport.RUGBY, "Six Nations", "6N", "rugby/180659",
            guideWords = listOf("Six Nations", "6 Nations"), regions = setOf("UK", "IE", "OTHER"), minutes = 110, color = 0xFF0A2463, national = true),
        League("rugby/289234", Sport.RUGBY, "Rugby Test Matches", "TESTS", "rugby/289234",
            guideWords = listOf("Summer Tour", "Rugby Union", "Test Match", "Lions Tour"),
            regions = setOf("UK", "IE", "AU", "OTHER"), minutes = 110, color = 0xFF1B4332, national = true),
        League("rugby/244293", Sport.RUGBY, "The Rugby Championship", "TRC", "rugby/244293",
            guideWords = listOf("Rugby Championship"), regions = setOf("AU", "OTHER"), minutes = 110, color = 0xFF002B5C, national = true),
        League("rugby/164205", Sport.RUGBY, "Rugby World Cup", "RWC", "rugby/164205",
            guideWords = listOf("Rugby World Cup"), regions = setOf("UK", "IE", "AU", "US", "OTHER"), minutes = 110, color = 0xFF0B4F2E, national = true),
        League("rugby/17567", Sport.RUGBY, "Nations Championship", "NATIONS", "rugby/17567",
            guideWords = listOf("Nations Championship", "Autumn Nations", "Autumn Internationals"), regions = setOf("UK", "IE", "AU", "OTHER"), minutes = 110, color = 0xFF14213D, national = true),
        rug("289237", "Women's Rugby World Cup", "WRWC", listOf("Women's Rugby World Cup"), color = 0xFF6A1B4D, women = true, national = true),
        // ---- Rugby union clubs
        rug("267979", "Premiership Rugby", "PREM", listOf("Premiership Rugby", "Gallagher Prem", "Gallagher Premiership"), setOf("UK", "IE"), 0xFF00205B),
        rug("270557", "United Rugby Championship", "URC", listOf("United Rugby Championship", "URC"), setOf("UK", "IE"), 0xFF0E2A47),
        rug("271937", "Champions Cup", "EPCR", listOf("Champions Cup", "Investec Champions Cup"), setOf("UK", "IE", "OTHER"), 0xFF0A1F44),
        rug("272073", "Challenge Cup", "CHALL", listOf("Challenge Cup", "EPCR Challenge Cup"), color = 0xFF2E4057),
        rug("270559", "Top 14", "TOP 14", listOf("Top 14"), setOf("OTHER"), 0xFF003F87),
        rug("242041", "Super Rugby Pacific", "SUPER", listOf("Super Rugby"), setOf("AU"), 0xFF00395D),
        rug("270563", "NPC (New Zealand)", "NPC", listOf("NPC", "Bunnings NPC", "Mitre 10 Cup"), setOf("AU"), 0xFF111111),
        rug("270555", "Currie Cup", "CURRIE", listOf("Currie Cup"), color = 0xFF006400),
        rug("289262", "Major League Rugby", "MLR", listOf("Major League Rugby", "MLR"), setOf("US"), 0xFF0B2545),
        // ---- Rugby league
        League("rugby-league/3", Sport.RUGBY, "NRL", "NRL", "rugby-league/3", guideWords = listOf("NRL", "National Rugby League", "NRL Premiership"),
            regions = setOf("AU"), minutes = 110, color = 0xFF1E2A5E),
        // ---- Cricket (live scores from ESPN; the guide's "Cricket" still finds the rest)
        League("cricket/8048", Sport.CRICKET, "Indian Premier League", "IPL", "cricket/8048", guideWords = listOf("IPL", "Indian Premier League"),
            regions = setOf("UK", "IE", "AU", "OTHER"), minutes = 230, color = 0xFF19398A),
        League("cricket/8044", Sport.CRICKET, "Big Bash League", "BBL", "cricket/8044", guideWords = listOf("Big Bash", "BBL", "KFC BBL"),
            regions = setOf("AU"), minutes = 200, color = 0xFF00A3E0),
        League("cricket/21275", Sport.CRICKET, "SA20", "SA20", "cricket/21275", guideWords = listOf("SA20", "Betway SA20"),
            regions = emptySet(), minutes = 220, color = 0xFF003B71),
        League("cricket/21266", Sport.CRICKET, "Major League Cricket", "MLC", "cricket/21266", guideWords = listOf("Major League Cricket", "MLC"),
            regions = US, minutes = 220, color = 0xFF0A2240),
        League("cricket/panel", Sport.CRICKET, "International Cricket", "INTL", "cricket/panel",
            guideWords = listOf("Test Match", "ODI", "One Day International", "T20 International", "The Ashes"),
            regions = setOf("UK", "IE", "AU", "OTHER"), minutes = 480, color = 0xFF0E5A3A),
        // ---- Aussie rules
        League("australian-football/afl", Sport.AUSSIE_RULES, "AFL", "AFL", "australian-football/afl", guideWords = listOf("AFL", "Aussie Rules"),
            regions = setOf("AU"), minutes = 150, color = 0xFF01285E),
        // ---- More football (soccer): followable everywhere, in All Events where they're watched
        soc("eng.5", "National League", "NL", listOf("National League", "Vanarama National League"), UK, 0xFF2C3E50),
        soc("eng.trophy", "EFL Trophy", "EFL TR", listOf("EFL Trophy", "Vertu Trophy"), color = 0xFF1C2C5B),
        soc("eng.w.fa", "Women's FA Cup", "WFA", listOf("Women's FA Cup"), color = 0xFF7A0019),
        soc("eng.w.league_cup", "Women's League Cup", "WLC", listOf("Women's League Cup", "Subway Cup"), color = 0xFF2C1E5A),
        soc("sco.2", "Scottish Championship", "SCO 2", listOf("Scottish Championship"), color = 0xFF2B2D7A),
        soc("sco.tennents", "Scottish Cup", "SCO CUP", listOf("Scottish Cup"), UK, 0xFF1F3A93),
        soc("sco.cis", "Scottish League Cup", "SCO LC", listOf("Scottish League Cup", "Premier Sports Cup"), UK, 0xFF2B2D7A),
        soc("uefa.wchampions", "Women's Champions League", "UWCL", listOf("Women's Champions League", "UWCL"), setOf("UK", "IE", "US"), 0xFF0B1E5B),
        soc("esp.2", "LaLiga 2", "LALIGA2", listOf("LaLiga 2", "LaLiga Hypermotion", "Segunda"), color = 0xFFEE3524),
        soc("ita.2", "Serie B", "SERIE B", listOf("Serie B"), color = 0xFF024494),
        soc("ger.2", "2. Bundesliga", "BUNDES2", listOf("2. Bundesliga", "Bundesliga 2"), color = 0xFFD20515),
        soc("fra.2", "Ligue 2", "LIGUE 2", listOf("Ligue 2"), color = 0xFF091C3E),
        soc("ned.1", "Eredivisie", "ERE", listOf("Eredivisie"), setOf("OTHER"), 0xFFE4002B),
        soc("por.1", "Primeira Liga", "POR", listOf("Primeira Liga", "Liga Portugal"), setOf("OTHER"), 0xFF003D7C),
        soc("bel.1", "Belgian Pro League", "BEL", listOf("Pro League", "Jupiler Pro League"), color = 0xFF1A1A1A),
        soc("tur.1", "Süper Lig", "TUR", listOf("Super Lig", "Süper Lig"), color = 0xFFE30A17),
        soc("gre.1", "Greek Super League", "GRE", listOf("Greek Super League"), color = 0xFF0D5EAF),
        soc("aut.1", "Austrian Bundesliga", "AUT", listOf("Austrian Bundesliga"), color = 0xFFED2939),
        soc("den.1", "Danish Superliga", "DEN", listOf("Superliga"), color = 0xFFC8102E),
        soc("swe.1", "Allsvenskan", "SWE", listOf("Allsvenskan"), color = 0xFF005293),
        soc("nor.1", "Eliteserien", "NOR", listOf("Eliteserien"), color = 0xFFBA0C2F),
        soc("rus.1", "Russian Premier League", "RUS", listOf("Russian Premier League"), color = 0xFF2B2D7A),
        soc("ksa.1", "Saudi Pro League", "SPL", listOf("Saudi Pro League", "Roshn Saudi League"), color = 0xFF006C35),
        soc("esp.copa_del_rey", "Copa del Rey", "CDR", listOf("Copa del Rey"), color = 0xFFAA151B),
        soc("ger.dfb_pokal", "DFB-Pokal", "POKAL", listOf("DFB-Pokal", "DFB Pokal", "German Cup"), color = 0xFF1A1A1A),
        soc("ita.coppa_italia", "Coppa Italia", "COPPA", listOf("Coppa Italia"), color = 0xFF009246),
        soc("fra.coupe_de_france", "Coupe de France", "CDF", listOf("Coupe de France"), color = 0xFF002395),
        soc("ned.cup", "KNVB Cup", "KNVB", listOf("KNVB Cup", "KNVB Beker"), color = 0xFFE4002B),
        soc("por.taca.portugal", "Taça de Portugal", "TACA", listOf("Taca de Portugal", "Taça de Portugal"), color = 0xFF003D7C),
        soc("uefa.super_cup", "UEFA Super Cup", "SUPER C", listOf("UEFA Super Cup", "Super Cup"), color = 0xFF0B1E5B),
        soc("eng.charity", "Community Shield", "SHIELD", listOf("Community Shield"), color = 0xFF7A0019),
        soc("fifa.cwc", "Club World Cup", "CWC", listOf("Club World Cup"), color = 0xFF56042C),
        soc("bra.1", "Brasileirão", "BRA", listOf("Brasileirao", "Brasileirão", "Serie A Brazil"), setOf("OTHER"), 0xFF009C3B),
        soc("arg.1", "Liga Profesional (Argentina)", "ARG", listOf("Liga Profesional", "Argentine"), setOf("OTHER"), 0xFF75AADB),
        soc("col.1", "Colombian Primera A", "COL", listOf("Primera A", "Liga BetPlay"), color = 0xFFFCD116),
        soc("chi.1", "Chilean Primera", "CHI", listOf("Chilean Primera"), color = 0xFFD52B1E),
        soc("conmebol.libertadores", "Copa Libertadores", "LIBERT", listOf("Copa Libertadores", "Libertadores"), setOf("US", "OTHER"), 0xFF1D1D1B),
        soc("conmebol.sudamericana", "Copa Sudamericana", "SUDAM", listOf("Copa Sudamericana", "Sudamericana"), color = 0xFF1D3C8F),
        soc("usa.usl.1", "USL Championship", "USL", listOf("USL Championship", "USL"), setOf("US"), 0xFF002F6C),
        soc("usa.usl.l1", "USL League One", "USL1", listOf("USL League One"), color = 0xFF002F6C),
        soc("usa.w.usl.1", "USL Super League", "USLS", listOf("USL Super League"), color = 0xFF5B2C6F),
        soc("usa.open", "U.S. Open Cup", "USOC", listOf("U.S. Open Cup", "US Open Cup"), setOf("US"), 0xFFB22234),
        soc("concacaf.champions", "CONCACAF Champions Cup", "CCC", listOf("Champions Cup", "Concacaf Champions"), setOf("US"), 0xFF002F6C),
        soc("concacaf.leagues.cup", "Leagues Cup", "LGS CUP", listOf("Leagues Cup"), setOf("US"), 0xFF001F5B),
        soc("mex.w.1", "Liga MX Femenil", "MX FEM", listOf("Liga MX Femenil"), color = 0xFF1A7F3C),
        soc("jpn.1", "J1 League", "J1", listOf("J1 League", "J.League"), color = 0xFFC8102E),
        soc("chn.1", "Chinese Super League", "CSL", listOf("Chinese Super League"), color = 0xFFDE2910),
        soc("ind.1", "Indian Super League", "ISL", listOf("Indian Super League", "ISL"), color = 0xFFFF9933),
        soc("aus.1", "A-League Men", "ALM", listOf("A-League"), setOf("AU"), 0xFF0E2D5A),
        soc("aus.w.1", "A-League Women", "ALW", listOf("A-League Women"), setOf("AU"), 0xFF0E2D5A),
        soc("afc.champions", "AFC Champions League Elite", "ACL", listOf("AFC Champions League"), color = 0xFF003876),
        soc("caf.champions", "CAF Champions League", "CAF CL", listOf("CAF Champions League"), color = 0xFF006233),
        soc("rsa.1", "South African Premiership", "PSL", listOf("Betway Premiership", "South African Premiership"), color = 0xFF007A4D),
        // Women's internationals: their own countries list (the Lionesses, the USWNT…)
        soc("fifa.wwc", "Women's World Cup", "WWC", listOf("Women's World Cup"), color = 0xFF56042C, women = true, national = true),
        soc("uefa.weuro", "Women's Euro", "WEURO", listOf("Women's Euro", "Women's European Championship"), color = 0xFF143CDB, women = true, national = true),
        soc("uefa.w.nations", "Women's Nations League", "WUNL", listOf("Women's Nations League"), color = 0xFF0E1B4D, women = true, national = true),
        soc("fifa.friendly.w", "Women's International Friendlies", "W FRIEND", listOf("Women's International", "Women's Friendly"), color = 0xFF33415C, women = true, national = true),
        // More from ESPN: Brazil's state leagues, the other World Cup qualifiers and continental
        // cups, US college soccer. (Ireland, Northern Ireland, Wales, Scottish League One and Two
        // and Switzerland aren't on ESPN any more: they come from TheSportsDB's files.)
        soc("bra.camp.paulista", "Campeonato Paulista", "PAULISTA", listOf("Paulista", "Paulistão"), color = 0xFF111111),
        soc("bra.camp.carioca", "Campeonato Carioca", "CARIOCA", listOf("Carioca", "Cariocão"), color = 0xFF0B4F2E),
        soc("bra.camp.mineiro", "Campeonato Mineiro", "MINEIRO", listOf("Mineiro"), color = 0xFF1A1A1A),
        soc("bra.camp.gaucho", "Campeonato Gaúcho", "GAUCHO", listOf("Gaúcho", "Gauchão"), color = 0xFF0B5394),
        soc("fifa.worldq.conmebol", "World Cup Qualifying (South America)", "WCQ SA", listOf("World Cup Qualifier", "World Cup Qualifying"), color = 0xFF003876, national = true),
        soc("fifa.worldq.afc", "World Cup Qualifying (Asia)", "WCQ AS", listOf("World Cup Qualifier", "World Cup Qualifying"), color = 0xFF1A3C6E, national = true),
        soc("fifa.worldq.caf", "World Cup Qualifying (Africa)", "WCQ AF", listOf("World Cup Qualifier", "World Cup Qualifying"), color = 0xFF006233, national = true),
        soc("fifa.worldq.ofc", "World Cup Qualifying (Oceania)", "WCQ OC", listOf("World Cup Qualifier", "World Cup Qualifying"), color = 0xFF1B4F72, national = true),
        soc("caf.nations", "Africa Cup of Nations", "AFCON", listOf("Africa Cup of Nations", "AFCON"), color = 0xFF006233, national = true),
        soc("afc.asian.cup", "AFC Asian Cup", "ASIAN", listOf("Asian Cup"), color = 0xFF1A3C6E, national = true),
        soc("fifa.olympics", "Olympic Football", "OLY", listOf("Olympic Football", "Olympics Football"), color = 0xFF0081C8),
        soc("club.friendly", "Club Friendlies", "FRIENDLY", listOf("Club Friendly", "Pre-Season Friendly", "Friendly"), color = 0xFF33415C),
        soc("eng.fa_qual", "FA Cup Qualifying", "FA Q", listOf("FA Cup Qualifying"), color = 0xFF7A0019),
        soc("sco.challenge", "Scottish Challenge Cup", "SCO CC", listOf("Scottish Challenge Cup", "SPFL Trust Trophy"), color = 0xFF2B2D7A),
        soc("ned.2", "Eerste Divisie", "NED 2", listOf("Eerste Divisie", "Keuken Kampioen Divisie"), color = 0xFFE87722),
        soc("esp.w.1", "Liga F", "LIGA F", listOf("Liga F"), color = 0xFF7A1F5C, women = true),
        soc("fra.w.1", "Première Ligue (Women)", "FRA W", listOf("Première Ligue", "Arkema Première Ligue"), color = 0xFF1F3A93, women = true),
        soc("ned.w.1", "Vrouwen Eredivisie", "NED W", listOf("Vrouwen Eredivisie"), color = 0xFFE87722, women = true),
        soc("esp.copa_de_la_reina", "Copa de la Reina", "REINA", listOf("Copa de la Reina"), color = 0xFF7A1F5C, women = true),
        soc("uefa.w.europa", "Women's Europa Cup", "UWEC", listOf("Women's Europa Cup"), color = 0xFF0E1B4D, women = true),
        soc("can.w.nsl", "Northern Super League", "NSL", listOf("Northern Super League"), setOf("CA"), 0xFF8B0000, women = true),
        soc("bra.2", "Brasileirão Série B", "BRA 2", listOf("Serie B Brazil", "Brasileirão Série B"), color = 0xFF009C3B),
        soc("bra.copa_do_brazil", "Copa do Brasil", "CDB", listOf("Copa do Brasil"), color = 0xFF009C3B),
        soc("arg.2", "Primera Nacional (Argentina)", "ARG 2", listOf("Primera Nacional", "Nacional B"), color = 0xFF6CACE4),
        soc("arg.3", "Primera B (Argentina)", "ARG 3", listOf("Primera B Metropolitana"), color = 0xFF6CACE4),
        soc("arg.copa", "Copa Argentina", "COPA ARG", listOf("Copa Argentina"), color = 0xFF6CACE4),
        soc("col.copa", "Copa Colombia", "COPA COL", listOf("Copa Colombia"), color = 0xFFFCD116),
        soc("chi.copa_chi", "Copa Chile", "COPA CHI", listOf("Copa Chile"), color = 0xFFD52B1E),
        soc("per.1", "Liga 1 (Peru)", "PER", listOf("Liga 1 Peru", "Liga1"), color = 0xFFD91023),
        soc("par.1", "Paraguayan Primera", "PAR", listOf("Primera División Paraguay"), color = 0xFFD52B1E),
        soc("par.1.supercopa", "Supercopa Paraguay", "SUPERCOPA", listOf("Supercopa Paraguay"), color = 0xFFD52B1E),
        soc("uru.1", "Liga AUF (Uruguay)", "URU", listOf("Liga AUF", "Uruguayan Primera"), color = 0xFF0038A8),
        soc("ecu.1", "LigaPro (Ecuador)", "ECU", listOf("LigaPro", "Liga Pro Ecuador"), color = 0xFFFFD100),
        soc("bol.1", "Bolivian Primera", "BOL", listOf("Liga Profesional Boliviana"), color = 0xFF007934),
        soc("ven.1", "Venezuelan Primera", "VEN", listOf("Liga FUTVE"), color = 0xFF8B1A1A),
        soc("mex.2", "Liga de Expansión MX", "MX 2", listOf("Liga de Expansión"), color = 0xFF00543D),
        soc("gua.1", "Liga Nacional (Guatemala)", "GUA", listOf("Liga Nacional Guatemala"), color = 0xFF4997D0),
        soc("hon.1", "Liga Nacional (Honduras)", "HON", listOf("Liga Nacional Honduras"), color = 0xFF0073CF),
        soc("crc.1", "Primera División (Costa Rica)", "CRC", listOf("Liga Promérica", "Costa Rica Primera"), color = 0xFF002B7F),
        soc("slv.1", "Primera División (El Salvador)", "SLV", listOf("Primera División El Salvador"), color = 0xFF0047AB),
        // On by default in the US and "Somewhere else" (Central America); named as Concacaf does, so searching "Concacaf" finds it.
        soc("concacaf.central.american.cup", "Concacaf Central American Cup", "CAC", listOf("Central American Cup", "Copa Centroamericana"), setOf("US", "OTHER"), color = 0xFF0B2545),
        soc("ksa.kings.cup", "King's Cup (Saudi)", "KINGS", listOf("King's Cup"), color = 0xFF006C35),
        soc("ind.2", "I-League", "I-LEAGUE", listOf("I-League"), color = 0xFFFF9933),
        soc("afc.cup", "AFC Champions League Two", "ACL2", listOf("AFC Champions League Two", "ACL Two"), color = 0xFF1A3C6E),
        soc("caf.confed", "CAF Confederation Cup", "CAF CC", listOf("Confederation Cup"), color = 0xFF006233),
        soc("fifa.world.u17", "U-17 World Cup", "U17 WC", listOf("U-17 World Cup", "Under-17 World Cup"), color = 0xFF326295),
        soc("fifa.wworld.u17", "Women's U-17 World Cup", "WU17", listOf("Women's U-17 World Cup"), color = 0xFF56042C, women = true),
        soc("uefa.euro_u21_qual", "U-21 Euro Qualifying", "U21 Q", listOf("Under-21", "U21 Euro"), color = 0xFF143CDB),
        soc("fifa.wworldq.uefa", "Women's World Cup Qualifying (Europe)", "WWCQ", listOf("Women's World Cup Qualifier"), color = 0xFF56042C, women = true, national = true),
        League("soccer/usa.ncaa.m.1", Sport.FOOTBALL, "Men's College Soccer", "NCAAS", "soccer/usa.ncaa.m.1",
            guideWords = listOf("College Soccer", "NCAA Soccer"), regions = emptySet(), minutes = 120, schoolNames = true, color = 0xFF2B4C7E),
        League("soccer/usa.ncaa.w.1", Sport.FOOTBALL, "Women's College Soccer", "NCAAWS", "soccer/usa.ncaa.w.1",
            guideWords = listOf("Women's College Soccer", "NCAA Women's Soccer"), regions = emptySet(), minutes = 120, schoolNames = true, color = 0xFF5B3A8C, women = true),
        // ---- More basketball, hockey, American football, baseball
        League("basketball/nba-development", Sport.BASKETBALL, "NBA G League", "G LEAGUE", "basketball/nba-development",
            guideWords = listOf("G League"), regions = emptySet(), minutes = 130, color = 0xFF002B5C),
        League("basketball/nbl", Sport.BASKETBALL, "NBL (Australia)", "NBL", "basketball/nbl",
            guideWords = listOf("NBL"), regions = setOf("AU"), minutes = 130, color = 0xFF1A1A1A),
        League("hockey/mens-college-hockey", Sport.HOCKEY, "Men's College Hockey", "NCAAH", "hockey/mens-college-hockey",
            guideWords = listOf("College Hockey", "NCAA Hockey"), regions = emptySet(), minutes = 160, schoolNames = true, color = 0xFF2B4C7E),
        League("football/cfl", Sport.AMERICAN_FOOTBALL, "CFL", "CFL", "football/cfl",
            guideWords = listOf("CFL", "Grey Cup"), regions = setOf("CA"), minutes = 195, color = 0xFFC8102E),
        League("football/ufl", Sport.AMERICAN_FOOTBALL, "UFL", "UFL", "football/ufl",
            guideWords = listOf("UFL"), regions = emptySet(), minutes = 190, color = 0xFF0B2545),
        League("baseball/college-baseball", Sport.BASEBALL, "College Baseball", "NCAAB", "baseball/college-baseball",
            guideWords = listOf("College Baseball", "NCAA Baseball"), regions = emptySet(), minutes = 190, schoolNames = true, color = 0xFF2B4C7E),
        League("volleyball/womens-college-volleyball", Sport.VOLLEYBALL, "Women's College Volleyball", "NCAAV", "volleyball/womens-college-volleyball",
            guideWords = listOf("College Volleyball", "NCAA Volleyball"), regions = emptySet(), minutes = 120, schoolNames = true, color = 0xFF5B3A8C),
        League("lacrosse/pll", Sport.LACROSSE, "Premier Lacrosse League", "PLL", "lacrosse/pll",
            guideWords = listOf("Premier Lacrosse", "PLL"), regions = emptySet(), minutes = 120, color = 0xFF111111),
        League("lacrosse/nll", Sport.LACROSSE, "National Lacrosse League", "NLL", "lacrosse/nll",
            guideWords = listOf("National Lacrosse League", "NLL"), regions = emptySet(), minutes = 150, color = 0xFF1D3C8F),
        // ---- More US college sports (ESPN)
        League("hockey/womens-college-hockey", Sport.HOCKEY, "Women's College Hockey", "NCAAWH", "hockey/womens-college-hockey",
            guideWords = listOf("Women's College Hockey", "NCAA Women's Hockey"), regions = emptySet(), minutes = 160, schoolNames = true, color = 0xFF5B3A8C, women = true),
        League("baseball/college-softball", Sport.BASEBALL, "College Softball", "NCAASB", "baseball/college-softball",
            guideWords = listOf("College Softball", "NCAA Softball", "Softball"), regions = emptySet(), minutes = 150, schoolNames = true, color = 0xFF5B3A8C, women = true),
        League("volleyball/mens-college-volleyball", Sport.VOLLEYBALL, "Men's College Volleyball", "NCAAMV", "volleyball/mens-college-volleyball",
            guideWords = listOf("Men's College Volleyball", "NCAA Men's Volleyball"), regions = emptySet(), minutes = 120, schoolNames = true, color = 0xFF2B4C7E),
        League("lacrosse/mens-college-lacrosse", Sport.LACROSSE, "Men's College Lacrosse", "NCAAL", "lacrosse/mens-college-lacrosse",
            guideWords = listOf("College Lacrosse", "NCAA Lacrosse"), regions = emptySet(), minutes = 140, schoolNames = true, color = 0xFF2B4C7E),
        League("lacrosse/womens-college-lacrosse", Sport.LACROSSE, "Women's College Lacrosse", "NCAAWL", "lacrosse/womens-college-lacrosse",
            guideWords = listOf("Women's College Lacrosse", "NCAA Women's Lacrosse"), regions = emptySet(), minutes = 130, schoolNames = true, color = 0xFF5B3A8C, women = true),
        // ---- Big tournaments (ESPN): only while they're on
        League("hockey/olympics-mens-ice-hockey", Sport.HOCKEY, "Olympic Hockey", "OLY", "hockey/olympics-mens-ice-hockey",
            guideWords = listOf("Olympic Hockey", "Olympics Ice Hockey", "Men's Ice Hockey"), regions = emptySet(), minutes = 160, color = 0xFF0081C8, national = true),
        League("hockey/olympics-womens-ice-hockey", Sport.HOCKEY, "Olympic Women's Hockey", "OLY W", "hockey/olympics-womens-ice-hockey",
            guideWords = listOf("Women's Olympic Hockey", "Women's Ice Hockey"), regions = emptySet(), minutes = 160, color = 0xFF0081C8, women = true, national = true),
        League("baseball/world-baseball-classic", Sport.BASEBALL, "World Baseball Classic", "WBC", "baseball/world-baseball-classic",
            guideWords = listOf("World Baseball Classic", "WBC"), regions = emptySet(), minutes = 190, color = 0xFF0A2240, national = true),
        // ---- Winter baseball (from late October) and more college sports
        League("baseball/dominican-winter-league", Sport.BASEBALL, "Dominican Winter League", "LIDOM", "baseball/dominican-winter-league",
            guideWords = listOf("LIDOM", "Dominican Winter League", "Liga Dominicana"), regions = emptySet(), minutes = 190, color = 0xFF002D62),
        League("baseball/puerto-rican-winter-league", Sport.BASEBALL, "Puerto Rican Winter League", "LBPRC", "baseball/puerto-rican-winter-league",
            guideWords = listOf("LBPRC", "Puerto Rico Winter League"), regions = emptySet(), minutes = 190, color = 0xFFED0A3F),
        League("baseball/venezuelan-winter-league", Sport.BASEBALL, "Venezuelan Winter League", "LVBP", "baseball/venezuelan-winter-league",
            guideWords = listOf("LVBP", "Venezuelan Winter League"), regions = emptySet(), minutes = 190, color = 0xFF8B1A1A),
        League("baseball/mexican-winter-league", Sport.BASEBALL, "Mexican Pacific League", "LMP", "baseball/mexican-winter-league",
            guideWords = listOf("LMP", "Liga Mexicana del Pacífico", "Mexican Pacific League"), regions = emptySet(), minutes = 190, color = 0xFF00543D),
        League("baseball/caribbean-series", Sport.BASEBALL, "Caribbean Series", "SERIE C", "baseball/caribbean-series",
            guideWords = listOf("Caribbean Series", "Serie del Caribe"), regions = emptySet(), minutes = 190, color = 0xFF0B5394),
        League("field-hockey/womens-college-field-hockey", Sport.OTHER, "College Field Hockey", "NCAAFH", "field-hockey/womens-college-field-hockey",
            guideWords = listOf("Field Hockey"), regions = emptySet(), minutes = 100, schoolNames = true, color = 0xFF5B3A8C, women = true),
        League("golf/ntw", Sport.GOLF, "Korn Ferry Tour", "KFT", "golf/ntw", LeagueKind.EVENT, guideWords = listOf("Korn Ferry Tour"),
            regions = emptySet(), minutes = 300, color = 0xFF003865),
        League("baseball/llb", Sport.BASEBALL, "Little League World Series", "LLWS", "baseball/llb",
            guideWords = listOf("Little League World Series", "Little League"), regions = emptySet(), minutes = 150, color = 0xFF0B5394),
        // ---- Hockey below the NHL and juniors (HockeyTech: the leagues' own live scores)
        League("hockeytech/ahl", Sport.HOCKEY, "AHL", "AHL", "hockeytech/ahl", guideWords = listOf("AHL", "American Hockey League"),
            regions = NA, minutes = 150, color = 0xFF003E7E),
        League("hockeytech/pwhl", Sport.HOCKEY, "PWHL", "PWHL", "hockeytech/pwhl", guideWords = listOf("PWHL", "Professional Women's Hockey"),
            regions = NA, minutes = 150, color = 0xFF2E1A47, women = true),
        League("hockeytech/echl", Sport.HOCKEY, "ECHL", "ECHL", "hockeytech/echl", guideWords = listOf("ECHL"),
            regions = emptySet(), minutes = 150, color = 0xFF1C3F94),
        League("hockeytech/ohl", Sport.HOCKEY, "OHL", "OHL", "hockeytech/ohl", guideWords = listOf("OHL", "Ontario Hockey League"),
            regions = emptySet(), minutes = 150, color = 0xFF0B3D91),
        League("hockeytech/whl", Sport.HOCKEY, "WHL", "WHL", "hockeytech/whl", guideWords = listOf("WHL", "Western Hockey League"),
            regions = emptySet(), minutes = 150, color = 0xFF111111),
        League("hockeytech/lhjmq", Sport.HOCKEY, "QMJHL", "QMJHL", "hockeytech/lhjmq", guideWords = listOf("QMJHL", "LHJMQ"),
            regions = emptySet(), minutes = 150, color = 0xFF003DA5),
        League("hockeytech/ushl", Sport.HOCKEY, "USHL", "USHL", "hockeytech/ushl", guideWords = listOf("USHL"),
            regions = emptySet(), minutes = 150, color = 0xFF002868),
        // ---- Minor League Baseball (MLB's own live scores)
        League("milb/11", Sport.BASEBALL, "Triple-A", "AAA", "milb/11", guideWords = listOf("Triple-A", "MiLB", "Minor League Baseball"),
            regions = emptySet(), minutes = 170, color = 0xFF0A2240),
        League("milb/12", Sport.BASEBALL, "Double-A", "AA", "milb/12", guideWords = listOf("Double-A", "MiLB"),
            regions = emptySet(), minutes = 170, color = 0xFF0A2240),
        League("milb/13", Sport.BASEBALL, "High-A", "HIGH-A", "milb/13", guideWords = listOf("High-A", "MiLB"),
            regions = emptySet(), minutes = 170, color = 0xFF0A2240),
        League("milb/14", Sport.BASEBALL, "Single-A", "A", "milb/14", guideWords = listOf("Single-A", "MiLB"),
            regions = emptySet(), minutes = 170, color = 0xFF0A2240),
        // ---- More events
        League("mma/pfl", Sport.FIGHTING, "PFL", "PFL", "mma/pfl", LeagueKind.EVENT, guideWords = listOf("PFL", "Professional Fighters League"),
            regions = emptySet(), minutes = 240, color = 0xFF111111),
        League("racing/nascar-secondary", Sport.RACING, "NASCAR O'Reilly Series", "NXS", "racing/nascar-secondary", LeagueKind.EVENT,
            guideWords = listOf("O'Reilly Auto Parts Series", "Xfinity Series"), regions = emptySet(), minutes = 150, color = 0xFF007AC2),
        League("racing/nascar-truck", Sport.RACING, "NASCAR Truck Series", "TRUCKS", "racing/nascar-truck", LeagueKind.EVENT,
            guideWords = listOf("Truck Series"), regions = emptySet(), minutes = 120, color = 0xFF007AC2),
        League("golf/liv", Sport.GOLF, "LIV Golf", "LIV", "golf/liv", LeagueKind.EVENT, guideWords = listOf("LIV Golf"),
            regions = emptySet(), minutes = 300, color = 0xFF111111),
        League("golf/champions-tour", Sport.GOLF, "PGA Tour Champions", "CHAMPS", "golf/champions-tour", LeagueKind.EVENT,
            guideWords = listOf("PGA Tour Champions"), regions = emptySet(), minutes = 300, color = 0xFF003865),
        // ---- Fighting
        League("mma/ufc", Sport.FIGHTING, "UFC", "UFC", "mma/ufc", LeagueKind.EVENT, guideWords = listOf("UFC"),
            regions = EVERYWHERE, minutes = 300, color = 0xFFD20A0A),
        League("guide/boxing", Sport.FIGHTING, "Boxing", "BOXING", guideWords = listOf("Boxing"), regions = EVERYWHERE,
            guideOnly = true, fixtureSlug = "boxing", kind = LeagueKind.EVENT, color = 0xFF7A1F1F),
        // ---- Wrestling (from the guide: weekly shows and big events)
        League("guide/wwe", Sport.WRESTLING, "WWE", "WWE",
            guideWords = listOf("WWE", "SmackDown", "Monday Night Raw", "WWE Raw", "NXT", "WrestleMania", "Royal Rumble", "SummerSlam", "Survivor Series"),
            regions = EVERYWHERE, guideOnly = true, kind = LeagueKind.EVENT, minutes = 120, color = 0xFF1C1C1C, liveByDefault = true),
        League("guide/aew", Sport.WRESTLING, "AEW", "AEW",
            guideWords = listOf("AEW", "AEW Dynamite", "AEW Collision", "AEW Rampage", "All Elite Wrestling"),
            regions = EVERYWHERE, guideOnly = true, kind = LeagueKind.EVENT, minutes = 120, color = 0xFF8C6D1F, liveByDefault = true),
        League("guide/wrestling", Sport.WRESTLING, "Wrestling", "WRESTLING", guideWords = listOf("TNA Wrestling", "TNA iMPACT", "NJPW", "Wrestling"),
            regions = setOf("US", "CA", "UK", "OTHER"), guideOnly = true, kind = LeagueKind.EVENT, color = 0xFF3A3A3A),
        // ---- Racing
        League("racing/f1", Sport.RACING, "Formula 1", "F1", "racing/f1", LeagueKind.EVENT,
            guideWords = listOf("Formula 1", "F1", "Grand Prix"), regions = EVERYWHERE, minutes = 120, color = 0xFFE10600),
        League("racing/nascar-premier", Sport.RACING, "NASCAR Cup", "NASCAR", "racing/nascar-premier", LeagueKind.EVENT,
            guideWords = listOf("NASCAR"), regions = US, minutes = 210, color = 0xFF007AC2),
        League("racing/irl", Sport.RACING, "IndyCar", "INDY", "racing/irl", LeagueKind.EVENT, guideWords = listOf("IndyCar"),
            regions = US, minutes = 180, color = 0xFF1E1E1E),
        League("guide/motogp", Sport.RACING, "MotoGP", "MOTOGP", guideWords = listOf("MotoGP"), regions = setOf("UK", "IE", "AU", "OTHER"),
            guideOnly = true, fixtureSlug = "motogp", kind = LeagueKind.EVENT, color = 0xFF231F20),
        // ---- Golf and tennis
        League("golf/pga", Sport.GOLF, "PGA Tour", "PGA", "golf/pga", LeagueKind.EVENT, guideWords = listOf("PGA Tour", "Golf"),
            regions = EVERYWHERE, minutes = 360, color = 0xFF003865),
        League("golf/eur", Sport.GOLF, "DP World Tour", "DPWT", "golf/eur", LeagueKind.EVENT, guideWords = listOf("DP World Tour", "Golf"),
            regions = setOf("UK", "IE", "OTHER"), minutes = 360, color = 0xFF0A1F44),
        League("golf/lpga", Sport.GOLF, "LPGA Tour", "LPGA", "golf/lpga", LeagueKind.EVENT, guideWords = listOf("LPGA"),
            regions = US, minutes = 300, color = 0xFF00205B),
        League("tennis/atp", Sport.TENNIS, "ATP Tour", "ATP", "tennis/atp", LeagueKind.EVENT, guideWords = listOf("ATP", "Tennis"),
            regions = EVERYWHERE, minutes = 600, color = 0xFF003A70),
        League("tennis/wta", Sport.TENNIS, "WTA Tour", "WTA", "tennis/wta", LeagueKind.EVENT, guideWords = listOf("WTA", "Tennis"),
            regions = EVERYWHERE, minutes = 600, color = 0xFF6A1B9A),
        // ---- Found in your guide (ESPN doesn't cover these)
        League("guide/rugby-union", Sport.RUGBY, "Rugby Union", "RUGBY",
            guideWords = listOf("Rugby Union"),
            regions = setOf("UK", "IE", "AU", "OTHER"), guideOnly = true, fixtureSlug = "rugby-union", kind = LeagueKind.EVENT, color = 0xFF0B4F2E),
        League("guide/rugby-league", Sport.RUGBY, "Rugby League", "RL",
            guideWords = listOf("Rugby League", "Super League", "NRL", "Challenge Cup", "State of Origin"),
            regions = setOf("UK", "AU", "OTHER"), guideOnly = true, fixtureSlug = "rugby-league", kind = LeagueKind.EVENT, color = 0xFF1D3C8F),
        League("guide/cricket", Sport.CRICKET, "Cricket", "CRICKET",
            guideWords = listOf("Cricket", "Test Match", "The Ashes", "IPL", "T20", "The Hundred", "ODI"),
            regions = setOf("UK", "IE", "AU", "OTHER"), guideOnly = true, fixtureSlug = "cricket", kind = LeagueKind.EVENT, color = 0xFF0E5A3A),
        League("guide/darts", Sport.DARTS, "Darts", "DARTS", guideWords = listOf("Darts"),
            regions = setOf("UK", "IE", "AU", "OTHER"), guideOnly = true, fixtureSlug = "darts", kind = LeagueKind.EVENT, color = 0xFF0F3D2E),
        League("guide/snooker", Sport.SNOOKER, "Snooker", "SNOOKER", guideWords = listOf("Snooker"),
            regions = setOf("UK", "IE", "OTHER"), guideOnly = true, fixtureSlug = "snooker", kind = LeagueKind.EVENT, color = 0xFF145A32),
        League("guide/horse-racing", Sport.OTHER, "Horse Racing", "RACING",
            guideWords = listOf("Horse Racing", "ITV Racing", "Racing TV", "Live Racing"),
            regions = setOf("UK", "IE", "AU", "OTHER"), guideOnly = true, fixtureSlug = "horse-racing", kind = LeagueKind.EVENT, color = 0xFF5D4037),
        League("guide/cycling", Sport.OTHER, "Cycling", "CYCLING", guideWords = listOf("Cycling", "Tour de France", "Giro", "Vuelta"),
            regions = setOf("UK", "IE", "OTHER"), guideOnly = true, fixtureSlug = "cycling", kind = LeagueKind.EVENT, color = 0xFFF2C200)
    )

    private val byId = leagues.associateBy { it.id }
    fun league(id: String): League? = byId[id] ?: extraById[id]

    /**
     * Leagues that arrive with the data rather than being built in: non-league football from
     * the nuvio-data files (ids "tsdb/<TheSportsDB id>"). Set when that data loads.
     */
    @Volatile var extra: List<League> = emptyList()
        private set
    @Volatile private var extraById: Map<String, League> = emptyMap()

    fun setExtra(list: List<League>) {
        extraById = list.associateBy { it.id }
        extra = list
    }

    /** Every league, built in and from the data. */
    val all: List<League> get() = leagues + extra

    private val UEFA_CLUB = listOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf")
    private val EURO_MORE = setOf("soccer/ned.1", "soccer/por.1", "soccer/bel.1", "soccer/tur.1", "soccer/gre.1", "soccer/aut.1", "soccer/den.1", "soccer/swe.1", "soccer/nor.1")
    /** A club's own country's cups. */
    private val DOMESTIC_CUPS = mapOf(
        "soccer/esp.1" to listOf("soccer/esp.copa_del_rey"), "soccer/esp.2" to listOf("soccer/esp.copa_del_rey"),
        "soccer/ger.1" to listOf("soccer/ger.dfb_pokal"), "soccer/ger.2" to listOf("soccer/ger.dfb_pokal"),
        "soccer/ita.1" to listOf("soccer/ita.coppa_italia"), "soccer/ita.2" to listOf("soccer/ita.coppa_italia"),
        "soccer/fra.1" to listOf("soccer/fra.coupe_de_france"), "soccer/fra.2" to listOf("soccer/fra.coupe_de_france"),
        "soccer/ned.1" to listOf("soccer/ned.cup"), "soccer/por.1" to listOf("soccer/por.taca.portugal"),
        "soccer/usa.1" to listOf("soccer/usa.open", "soccer/concacaf.champions", "soccer/concacaf.leagues.cup"),
        "soccer/mex.1" to listOf("soccer/concacaf.champions", "soccer/concacaf.leagues.cup"),
        "soccer/usa.usl.1" to listOf("soccer/usa.open"), "soccer/usa.usl.l1" to listOf("soccer/usa.open"),
        "soccer/bra.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana", "soccer/bra.copa_do_brazil"),
        "soccer/bra.2" to listOf("soccer/bra.copa_do_brazil"),
        "soccer/arg.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana", "soccer/arg.copa"),
        "soccer/arg.2" to listOf("soccer/arg.copa"),
        "soccer/col.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana", "soccer/col.copa"),
        "soccer/chi.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana", "soccer/chi.copa_chi"),
        "soccer/per.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana"),
        "soccer/par.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana", "soccer/par.1.supercopa"),
        "soccer/uru.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana"),
        "soccer/ecu.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana"),
        "soccer/bol.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana"),
        "soccer/ven.1" to listOf("soccer/conmebol.libertadores", "soccer/conmebol.sudamericana"),
        "soccer/esp.w.1" to listOf("soccer/esp.copa_de_la_reina", "soccer/uefa.wchampions"),
        "soccer/jpn.1" to listOf("soccer/afc.champions", "soccer/afc.cup"), "soccer/ksa.1" to listOf("soccer/afc.champions", "soccer/afc.cup", "soccer/ksa.kings.cup"),
        "soccer/chn.1" to listOf("soccer/afc.champions"), "soccer/aus.1" to listOf("soccer/afc.champions"),
        "soccer/rsa.1" to listOf("soccer/caf.champions"),
        "rugby/267979" to listOf("rugby/271937", "rugby/272073"), "rugby/270557" to listOf("rugby/271937", "rugby/272073"),
        "rugby/270559" to listOf("rugby/271937", "rugby/272073")
    )
    private val EURO_TOP = setOf("soccer/esp.1", "soccer/ita.1", "soccer/ger.1", "soccer/fra.1", "soccer/sco.1") + UEFA_CLUB

    /**
     * The other competitions a team followed in [leagueId] plays in: a country's every
     * international competition; an English club's European games and cups; a club from another
     * top European league, its European games.
     */
    fun relatedLeagues(leagueId: String): List<String> {
        val l = league(leagueId) ?: return emptyList()
        val out = when {
            l.national -> leagues.filter { it.national && it.nationalGroup == l.nationalGroup }.map { it.id }
            leagueId.startsWith("soccer/eng.w") -> listOf("soccer/eng.w.fa", "soccer/eng.w.league_cup", "soccer/uefa.wchampions")
            leagueId in setOf("soccer/eng.2", "soccer/eng.3", "soccer/eng.4", "soccer/eng.5") ->
                listOf("soccer/eng.fa", "soccer/eng.league_cup", "soccer/eng.trophy")
            leagueId.startsWith("soccer/eng.") ->
                UEFA_CLUB + listOf("soccer/eng.fa", "soccer/eng.league_cup", "soccer/uefa.super_cup", "soccer/eng.charity", "soccer/fifa.cwc")
            leagueId.startsWith("soccer/sco.") -> UEFA_CLUB + listOf("soccer/sco.tennents", "soccer/sco.cis")
            else -> (if (leagueId in EURO_TOP || leagueId in EURO_MORE) UEFA_CLUB + listOf("soccer/uefa.super_cup", "soccer/fifa.cwc") else emptyList()) +
                DOMESTIC_CUPS[leagueId].orEmpty()
        }
        return out.filter { it != leagueId }
    }

    /**
     * Leagues whose team ids are shared: ESPN's football (soccer) competitions all use the same
     * club and country ids; the non-league files use TheSportsDB's. Other sports: the league itself.
     */
    fun teamFamily(leagueId: String): String = when {
        leagueId.startsWith("tsdb/") -> "tsdb"
        leagueId.startsWith("soccer/") -> "soccer"
        // ESPN's rugby competitions share team ids too (England is 1 in all of them).
        leagueId.startsWith("rugby/") -> "rugby"
        leagueId.startsWith("lacrosse/") -> "lacrosse"
        else -> leagueId
    }

    /** The TV's country, as a Sports region. */
    fun deviceRegion(): String = when (SportsPlatform.countryCode()) {
        "US", "PR" -> "US"
        "GB", "UK" -> "UK"
        "IE" -> "IE"
        "CA" -> "CA"
        "AU", "NZ" -> "AU"
        else -> "OTHER"
    }

    fun regionOf(s: SportsSettings): String = s.region.ifBlank { deviceRegion() }

    val regions: List<Pair<String, String>> = listOf(
        "US" to "United States",
        "UK" to "United Kingdom",
        "IE" to "Ireland",
        "CA" to "Canada",
        "AU" to "Australia & New Zealand",
        "OTHER" to "Somewhere else"
    )

    fun regionName(code: String): String = regions.firstOrNull { it.first == code }?.second ?: code

    /**
     * In All Events: the region's leagues, with your own choices from the Leagues dialog applied
     * on top (anything you follow is added separately, see SportsRepository.activeLeagues).
     * [SportsSettings.hiddenLeagues] holds a plain league id for one taken off that's normally
     * shown in your region, and "+id" for one added from outside it.
     */
    fun shown(league: League, s: SportsSettings): Boolean {
        val inRegion = regionOf(s) in league.regions
        return when {
            inRegion && league.id in s.hiddenLeagues -> false
            !inRegion && "+${league.id}" in s.hiddenLeagues -> true
            else -> inRegion
        }
    }

    /** [s] with [league] turned on or off for All Events (see [shown] for how the choice is stored). */
    fun toggleShown(league: League, s: SportsSettings): SportsSettings {
        val inRegion = regionOf(s) in league.regions
        val on = shown(league, s)
        val h = s.hiddenLeagues.toMutableSet()
        if (inRegion) {
            if (on) h += league.id else h -= league.id
        } else {
            if (on) h -= "+${league.id}" else h += "+${league.id}"
        }
        return s.copy(hiddenLeagues = h)
    }

    /** Leagues in the order the region cares about them. */
    fun ordered(s: SportsSettings): List<League> {
        val region = regionOf(s)
        val first: List<Sport> = when (region) {
            "UK", "IE" -> listOf(Sport.FOOTBALL, Sport.RUGBY, Sport.CRICKET, Sport.DARTS, Sport.SNOOKER, Sport.RACING, Sport.FIGHTING, Sport.WRESTLING, Sport.GOLF, Sport.TENNIS)
            "AU" -> listOf(Sport.AUSSIE_RULES, Sport.RUGBY, Sport.CRICKET, Sport.FOOTBALL, Sport.BASKETBALL)
            "CA" -> listOf(Sport.HOCKEY, Sport.AMERICAN_FOOTBALL, Sport.BASKETBALL, Sport.BASEBALL)
            else -> listOf(Sport.AMERICAN_FOOTBALL, Sport.BASKETBALL, Sport.BASEBALL, Sport.HOCKEY, Sport.FOOTBALL)
        }
        return all.sortedBy { l -> first.indexOf(l.sport).let { if (it < 0) 100 + l.sport.ordinal else it } }
    }

    /** The sports in the region's order. */
    fun sportsOrder(s: SportsSettings): List<Sport> = ordered(s).map { it.sport }.distinct()

    // ------------------------------------------------------------------ names, for the guide

    /**
     * Lowercase with accents, punctuation and "&" sorted out, padded with spaces so whole words
     * can be looked for with [hasWord].
     */
    /**
     * Small raised letters some guides put in titles ("ᴸᴵⱽᴱ", "ᴺᴱᵂ", "ʟɪᴠᴇ"): superscript and
     * small-capital letters, and the ordinary letter each one stands for.
     */
    private const val SMALL = "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᶦᶫᶰᶸᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ⁰¹²³⁴⁵⁶⁷⁸⁹⁺"
    private const val PLAIN = "abdeghijklmnoprtuvwabcdefghijklmnoprstuvwxyzilnuabcdefghijklmnoprstuvwyz0123456789+"
    private val SMALL_RUN by lazy { Regex("[$SMALL]+(?:\\s*[$SMALL]+)*") }

    /** The word a run of small raised letters spells ("ᴸᴵⱽᴱ" -> "live"). */
    private fun plainOf(run: String): String = buildString {
        run.forEach { ch -> val i = SMALL.indexOf(ch); if (i >= 0) append(PLAIN[i]) else if (ch.isWhitespace()) append(' ') }
    }

    /** Small raised letters as ordinary ones, as their own word ("Collision ᴸᴵⱽᴱ" -> "Collision live"). */
    fun plainSmall(s: String): String =
        if (s.none { it in SMALL }) s else s.replace(SMALL_RUN) { m -> " " + plainOf(m.value) + " " }

    private val SMALL_MARKS = setOf("live", "new", "hd", "fhd", "uhd", "4k")

    /** [s] without small raised "ᴸᴵⱽᴱ", "ᴺᴱᵂ" and "ᴴᴰ" (they're marks, not part of the name). */
    fun withoutSmallMarks(s: String): String =
        if (s.none { it in SMALL }) s
        else s.replace(SMALL_RUN) { m -> if (plainOf(m.value).split(' ').filter { it.isNotBlank() }.all { it in SMALL_MARKS }) " " else m.value }
            .replace(Regex("\\s+"), " ").trim()

    fun norm(s: String): String {
        val plain = SportsPlatform.foldAccents(plainSmall(s))
        return " " + plain.lowercase().replace("&", " and ").replace(NOT_WORD, " ").trim().replace(SPACES, " ") + " "
    }

    /**
     * A fight's two sides, from a title like "Canelo Álvarez vs Christian Mbilli", "UFC 310: Pantoja
     * vs. Asakura" or "Boxing : Canelo v Mbilli": each side's name words (4 letters or more, without
     * card or promoter words), or null when the title isn't "someone vs someone".
     */
    fun fightSides(title: String): Pair<List<String>, List<String>>? {
        val parts = norm(title).trim().split(" vs ", " v ", " versus ")
        if (parts.size != 2) return null
        fun words(part: String) = part.trim().split(' ')
            .filter { w -> w.length >= 4 && w.any { it.isLetter() } && w !in FIGHT_NOISE }
            .distinct()
        val a = words(parts[0])
        val b = words(parts[1])
        return if (a.isEmpty() || b.isEmpty()) null else a to b
    }

    private val FIGHT_NOISE = setOf(
        "boxing", "fight", "fights", "night", "card", "main", "event", "live", "title", "world", "championship",
        "undercard", "prelims", "preliminary", "presents", "from", "replay", "full", "saturday", "friday", "sunday",
        "season", "riyadh", "zuffa", "showdown", "heavyweight", "middleweight", "welterweight", "lightweight",
        "featherweight", "bantamweight", "flyweight", "cruiserweight", "rematch", "undisputed", "unified", "bout"
    )

    /** [term] (already normalized, no padding) as whole words in [normText] (from [norm]). */
    fun hasWord(normText: String, term: String): Boolean = term.isNotBlank() && normText.contains(" $term ")

    private val NOT_WORD = Regex("[^a-z0-9]+")
    private val SPACES = Regex(" +")

    /** Words that make a show a replay or a highlights show rather than the game itself. */
    val NOT_LIVE_WORDS = listOf(
        "highlights", "replay", "classic", "classics", "rewind", "recap", "review", "reaction",
        "condensed", "mini match", "extended", "best of", "in 60", "in 90", "encore", "re air", "reair",
        "magazine", "preview show", "goals", "match of the day", "motd", "uncut", "repeat",
        "this week in", "bottom line", "untold", "vault", "biography",
        // UK guides shorten highlights ("PL: Brighton v Leeds Hlts"); re-runs of a game shown "as live".
        "hlts", "highlight", "rerun", "re run", "as live"
    )

    /** Other names guides use for teams (and the start of names that need them). */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "manchester united" to listOf("man utd", "man united", "man u"),
        "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("tottenham", "spurs"),
        "wolverhampton wanderers" to listOf("wolves", "wolverhampton"),
        "brighton and hove albion" to listOf("brighton"),
        "west ham united" to listOf("west ham"),
        "newcastle united" to listOf("newcastle"),
        "nottingham forest" to listOf("nottm forest", "nottingham f"),
        "leicester city" to listOf("leicester"),
        "leeds united" to listOf("leeds"),
        "sheffield united" to listOf("sheff utd", "sheffield utd"),
        "sheffield wednesday" to listOf("sheff wed", "sheffield wed"),
        "queens park rangers" to listOf("qpr"),
        "west bromwich albion" to listOf("west brom", "wba"),
        "afc bournemouth" to listOf("bournemouth"),
        "ipswich town" to listOf("ipswich"),
        "luton town" to listOf("luton"),
        "aston villa" to listOf("villa"),
        "crystal palace" to listOf("palace"),
        "paris saint germain" to listOf("psg", "paris sg", "paris st germain"),
        "internazionale" to listOf("inter milan", "internazionale milano"),
                "bayern munich" to listOf("bayern", "fc bayern", "bayern munchen"),
        "borussia dortmund" to listOf("dortmund", "bvb"),
        "bayer leverkusen" to listOf("leverkusen"),
        "atletico madrid" to listOf("atletico", "atleti"),
        "barcelona" to listOf("barca", "fc barcelona"),
        "celtic" to listOf("celtic fc"),
        "rangers" to listOf("rangers fc"),
        "inter miami cf" to listOf("inter miami"),
        "la galaxy" to listOf("galaxy"),
        "los angeles fc" to listOf("lafc"),
        "new york red bulls" to listOf("red bulls", "ny red bulls"),
        "new york city fc" to listOf("nycfc", "nyc fc")
    )

    /** "FC", "AFC" and similar on the end or start of names: guides often leave them off. */
    private val CLUB_BITS = Regex("""^(fc|afc|cf|sc|ac|ss|ssc|as|rc|cd|ca|sv|vfb|vfl|tsg|1 fc) | (fc|afc|cf|sc)$""")

    /**
     * How a guide might name [team]: strong names (enough on their own, with the other team) and
     * weak ones (a city: Detroit plays four sports). College teams go by their school.
     */
    fun teamNames(team: TeamInfo, league: League): Pair<List<String>, List<String>> {
        val strong = LinkedHashSet<String>()
        val weak = LinkedHashSet<String>()
        fun n(s: String?) = s?.let { norm(it).trim() }?.takeIf { it.length >= 3 }
        val full = n(team.fullName)
        full?.let { strong += it; strong += it.replace(CLUB_BITS, "").trim() }
        val nick = n(team.name)
        val loc = n(team.location)
        if (league.schoolNames) {
            loc?.let { strong += it }
            nick?.let { if (loc == null || nick != loc) weak += it }
        } else if (league.sport == Sport.FOOTBALL) {
            nick?.let { strong += it; strong += it.replace(CLUB_BITS, "").trim() }
            loc?.let { if (loc != nick) weak += it }
        } else {
            nick?.let { if (it != loc) strong += it }
            loc?.let { weak += it }
        }
        listOfNotNull(full, nick).forEach { k -> ALIASES[k]?.let { strong += it } }
        strong.removeAll { it.length < 3 }
        weak.removeAll(strong)
        return strong.toList() to weak.toList()
    }
}

/**
 * A show's name without the date some schedules put in it: "NXT - October 20th 2026" -> "NXT",
 * "Raw: 20/10/2026" -> "Raw", "SmackDown (Oct 24, 2026)" -> "SmackDown". The card shows the date
 * anyway. A name that's only a date is left as it is.
 */
internal fun withoutAirDate(title: String): String {
    val month = """(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?"""
    val day = """\d{1,2}(?:st|nd|rd|th)?"""
    val date = listOf(
        """$month\s+$day,?\s+\d{4}""",        // October 20th 2026, Oct 20, 2026
        """$day\s+$month,?\s+\d{4}""",        // 20th October 2026
        """\d{4}-\d{2}-\d{2}""",              // 2026-10-20
        """\d{1,2}[/.]\d{1,2}[/.]\d{2,4}"""   // 20/10/2026, 10.20.26
    ).joinToString("|")
    val trailing = Regex("""\s*(?:[\-–—:|,]\s*)?\(?(?:$date)\)?\s*$""", RegexOption.IGNORE_CASE)
    val out = title.replace(trailing, "").trim().trimEnd('-', '–', '—', ':', '|', ',').trim()
    return out.ifBlank { title }
}

/**
 * A wrestling show's headline: without the promotion in front (its logo is beside it) and without
 * the episode number. A special episode's own name wins: "Collision #168 Fright Night Collision"
 * -> "Fright Night Collision", "RAW #1743" -> "RAW", "WWE Friday Night SmackDown" -> "Friday Night SmackDown".
 */
internal fun wrestlingHeadline(title: String): String {
    val t = withoutAirDate(title).replace(Regex("""(?i)^\s*(wwe|aew|tna|njpw)\s*[:\-–]?\s*"""), "").trim()
    val m = Regex("""^(.*?)\s*#\s*\d+\s*(.*)$""").find(t)
    val out = if (m != null) m.groupValues[2].trim().ifBlank { m.groupValues[1].trim() } else t
    return out.ifBlank { title }
}

private fun soc(path: String, name: String, short: String, words: List<String>, regions: Set<String> = emptySet(), color: Long = 0xFF2A2F3A, women: Boolean = false, national: Boolean = false) =
    League("soccer/$path", Sport.FOOTBALL, name, short, "soccer/$path", guideWords = words, regions = regions, minutes = if (path.contains("cup") || path.contains("copa") || path.contains("pokal") || path.contains("taca") || path.contains("coupe")) 125 else 115,
        color = color, women = women, national = national)

private fun rug(id: String, name: String, short: String, words: List<String>, regions: Set<String> = emptySet(), color: Long = 0xFF1B4332, women: Boolean = false, national: Boolean = false) =
    League("rugby/$id", Sport.RUGBY, name, short, "rugby/$id", guideWords = words, regions = regions, minutes = 110, color = color, women = women, national = national)
