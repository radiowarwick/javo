// TO BE DONE: Crossfading needs to be calculated when adding to DB. Check it's actualy adding to the correct end time!

// Re-sort the views on raw as they are fucked

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Stack;
import java.util.Arrays;
import java.time.*;
import java.lang.Thread;
import java.lang.Integer;

public class javoScheduler{


/////////////////////////////////////////////////////////////////////////////////
//								Global variables							   //
/////////////////////////////////////////////////////////////////////////////////
/*   CONSTANTS   */
final static int minutesToWorkAhead = 60;
final static int numberOfSongsToScheduleBeforeJingle = 3;
final static int minutesInTheHourToPlayAds[] = {30};
final static int SAMPLE_RATE = 44100;
    
/*   USED IN LOOP   */
static int currentPlaylist = 0; // <- Default!
static int currentCountOfSongsBeforeJingle = 0;
static Stack<Track> songsInTheCurrentPlaylist = new Stack<Track>();
static boolean playedAdsInTheHour[] = new boolean[minutesInTheHourToPlayAds.length]; // boolean rep for each of the values in minutesInTheHourToPlayAds
static int currentStopTime = 0;
static int currentScheduledHour = 0;
static int lastPreRec = 0;

/*   DATABASE CONNECTION   */
static Connection databaseConn = null;

/*   LOGGING DATA   */
static String logFile = "0";
static int currentDate = 0;

/*   OVER-SCHEDULE RECOVERY   */
static int maxScheduled = 100;

/////////////////////////////////////////////////////////////////////////////////
//									Entry point							  	   //
/////////////////////////////////////////////////////////////////////////////////
public static void main(String args[]){

	// connect to the database
	databaseConn = connectToDatabaseOrDie();

	try {
		while(true) {
			// 23:00 on Monday 16th Mar (Happy St Patricks Day tomorrow!)
			// Last logged = 1426546800
			// +1 hour = 1426550400
			int epoch = (int) (System.currentTimeMillis() / 1000L);
			
			try {
				addToScheduleUntilTime(epoch + 3600); // schedule 1 hour ahead of now
				printSchedule();
			}
			catch (FileNotFoundException e){
				System.out.println("Caught!");
			}

			Thread.sleep(300000); // Sleeps for 5 minutes
		}
	}
	catch (InterruptedException e){
		System.out.println("Caught!");
	}

	System.out.println("We finished!");
}



/////////////////////////////////////////////////////////////////////////////////
//									The scheduler						  	   //
/////////////////////////////////////////////////////////////////////////////////
private static void addToScheduleUntilTime(int timeToAddUntil) throws FileNotFoundException {
    // Assume that timeToAddUntil is a unix timestamp

    // Calculate the current stop time
    int currentStopTime = (int) getCurrentSongFinishTime() + getSchedulerSecondsRemaining();

    if (getScheduleRemainingSize() > maxScheduled) {
    	// Output overschedule warning
    	logToFile("[ERROR] Over-schedule detected, " + getScheduleRemainingSize() + " items found, requesting queue to be cleared.");
    	// Clear the queue
		clearSchedule();
    	// Reset current stop time
    	currentStopTime = (int) getCurrentSongFinishTime() + getSchedulerSecondsRemaining();
    }

    System.out.println("addToScheduleUntilTime: Scheduling from " + currentStopTime + " til " + timeToAddUntil);
    logToFile("[TIME] Scheduling from " + epochConverter(currentStopTime) + " until " + epochConverter(timeToAddUntil));

    int remainingTime = getSchedulerSecondsRemaining();
    int remainingTimeSecounds = remainingTime % 60;
	int remainingTimeMinutes = (int) (Math.floor(remainingTime / 60) % 60);
	int remainingTimeHours = (int) Math.floor(remainingTimeMinutes / 60);    
    logToFile("[TIME] Schedule length remaining: " + Integer.toString(remainingTimeHours) + ":" + Integer.toString(remainingTimeMinutes) + ":" + Integer.toString(remainingTimeSecounds) + ".");
    
    // Keep adding to schedule until we've added enough
    while(currentStopTime < timeToAddUntil) {

        // LocalDateTime t = new LocalDateTime((int) currentStopTime);
        LocalDateTime thetime = LocalDateTime.ofEpochSecond((long) currentStopTime, 0, ZoneOffset.of("Z"));
		ZonedDateTime instant = ZonedDateTime.of(thetime, ZoneId.of("GMT"));
		LocalDateTime t = instant.withZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime();


		if (currentDate != t.getDayOfMonth()) { // move into a new day

			String theDate = Integer.toString(t.getDayOfMonth());
			if (theDate.length() == 1){
				theDate = "0" + Integer.toString(t.getDayOfMonth());
			}

			String theMonth = Integer.toString(t.getMonthValue());
			if (theMonth.length() == 1) {
				theMonth = "0" + Integer.toString(t.getMonthValue());
			}

			logFile = theDate + theMonth + Integer.toString(t.getYear());

			logToFile("[INFO] Successful log file rotate.");
			currentDate = t.getDayOfMonth();
			
		}

        if (t.getHour() != currentScheduledHour) { // moves into a new hour

        	System.out.println("The current hour is: " + t.getHour());
        	logToFile("[JAVO] The current hour being scheduled is: " + t.getHour() + ":00.");

            /*
            Reset
            - Work out if the currentStopTime goes into the next hour
            If it does:    ///////- Reset the adverts boolean array
                            //////- find out if there's a prerecord. If so, add to the schedule.
                               /////// - Else find out which playlist we need to be in for this hour (if not one, default to 0)
                            - If the playlist changed, empty the stack, repopulate, randomise
            */

            for (int i=0; i<playedAdsInTheHour.length; i++) {
                playedAdsInTheHour[i] = false; // Reset all ads to 0
            }

            Track possiblePrerecord = getPrerecordForTime(t);
            int possiblePlaylist = getPlaylistForTime(t);

            // if oldPlaylist = newPlaylist then we haven't changed playlist, so no need to empty the queue and repopulate.
            if (possiblePrerecord.getAudioID() != -1 && !(possiblePrerecord.getAudioID() == lastPreRec)) {
                // schedule prerecord
                // songsInTheCurrentPlaylist[] = getPrerecordForTime();
                System.out.println(":::CHANGE OF HOUR::: PRERECORD DETECTED -> " + possiblePrerecord.getAudioID());
                logToFile("[JAVO] ::CHANGE OF HOUR:: Prerecord detected. Scheduling audio id: " + possiblePrerecord.getAudioID() + ".");
                addAudioToSchedule(possiblePrerecord);
                currentStopTime = (int) getCurrentSongFinishTime() + getSchedulerSecondsRemaining();
                lastPreRec = possiblePrerecord.getAudioID();
                currentScheduledHour = t.getHour();
                continue; // Added an item to the schedule and updated time, so go back round
            }
            else if (currentPlaylist == possiblePlaylist) {
            	System.out.println("::: CHANGE OF HOUR::: IDENTICAL PLAYLIST -> " + currentPlaylist);
            	logToFile("[JAVO] ::CHANGE OF HOUR:: Identical playlist detected. Playlist id: " + currentPlaylist + ".");
            }
            else if (possiblePlaylist != -1){
            	System.out.println(":::CHANGE OF HOUR::: PLAYLIST FOR HOUR -> " + possiblePlaylist);
            	logToFile("[JAVO] ::CHANGE OF HOUR:: Playlist has changed. Playlist id: " + possiblePlaylist + ".");
                // This is the only update of current playlist!! Therefore it is used to check for a playlist change.
                currentPlaylist = possiblePlaylist;
                songsInTheCurrentPlaylist.empty();
                Track[] songsInPlaylist = getSongsInPlaylist(currentPlaylist);
				for(Track songID:songsInPlaylist) songsInTheCurrentPlaylist.push(songID);
            }
            else { // no valid playlist for this hour, default to playlist 0 (general)
                System.out.println(":::CHANGE OF HOUR::: NO PLAYLIST FOR HOUR -> DEFAULTING TO 0");
                logToFile("[JAVO] ::CHANGE OF HOUR:: No playlist data for the current hour. Defaulting to 0.");
                songsInTheCurrentPlaylist.empty();
				Track[] songsInPlaylist = getSongsInPlaylist(0);
				for(Track songID:songsInPlaylist) songsInTheCurrentPlaylist.push(songID);
            }

            currentScheduledHour = t.getHour();

        } 


        if (songsInTheCurrentPlaylist.size() < 1){
            // We've played every song in the playlist, start playing them again
            Track[] songsInPlaylist = getSongsInPlaylist(currentPlaylist);
			for(Track songID:songsInPlaylist) songsInTheCurrentPlaylist.push(songID);
        }

	    if (songsInTheCurrentPlaylist.size() < 1){
            // We've played every song in the playlist, start playing them again
            System.err.println("Tried to get a song from playlist " + currentPlaylist + " but it's definitely empty!!!");
        }

        // --------------- ADVERT SCHEDULING --------------- //
        for (int i=0; i < minutesInTheHourToPlayAds.length; i++){
            if (((currentStopTime % 3600) > (minutesInTheHourToPlayAds[i] * 60)) && (playedAdsInTheHour[i] == false)){
            	System.out.println("should be adding adverts now");
            	logToFile("[JAVO] Commencing advert scheduling.");
                Track[] advertsToPlay = getAdverts();
                for (int ad=0; ad<advertsToPlay.length; ad++) addAudioToSchedule(advertsToPlay[ad]);
                playedAdsInTheHour[i] = true;
            }
        }

        // --------------- MUSIC SCHEDULING ---------------- //
        addAudioToSchedule(songsInTheCurrentPlaylist.pop());
        currentCountOfSongsBeforeJingle++;

        // --------------- JINGLE SCHEDULING --------------- //
        if (currentCountOfSongsBeforeJingle >= numberOfSongsToScheduleBeforeJingle){
            // Play a jingle!
            if (getRandomJingle().getAudioID() != -1) {
            	System.out.println("--- Adding jingle to queue ---");
            	logToFile("[JAVO] Song limit reached, inserting a jingle.");
	            addAudioToSchedule(getRandomJingle());
	            currentCountOfSongsBeforeJingle = 0;
	        } else {
	        	System.out.println("--- Attempted to add a jingle but none was found ---");
	        	logToFile("[WARNING] Jingle was not scheduled as no jingles were found.");
	        }
        }
    
        currentStopTime = (int) getCurrentSongFinishTime() + getSchedulerSecondsRemaining();
    }

}



/////////////////////////////////////////////////////////////////////////////////
//								Database functions						  	   //
/////////////////////////////////////////////////////////////////////////////////
private static Connection connectToDatabaseOrDie(){
	Connection conn = null;
	try {
		Class.forName("org.postgresql.Driver");
		String url = "jdbc:postgresql://localhost/digiplay";
		conn = DriverManager.getConnection(url,"digiplay_user", "");
	}
	catch (ClassNotFoundException e){
		e.printStackTrace();
		System.exit(1);
	}
	catch (SQLException e){
		e.printStackTrace();
		System.exit(2);
	}
	return conn;
}



/////////////////////////////////////////////////////////////////////////////////
//								Logging functions						  	   //
/////////////////////////////////////////////////////////////////////////////////
private static void logToFile(String message) throws FileNotFoundException {

	long currentLogTime = System.currentTimeMillis()/1000;
	LocalDateTime logTime = LocalDateTime.ofEpochSecond((long) currentLogTime, 0, ZoneOffset.of("Z"));
	ZonedDateTime logInstant = ZonedDateTime.of(logTime, ZoneId.of("GMT"));
	LocalDateTime actualLogTime = logInstant.withZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime();

	String theHour = Integer.toString(actualLogTime.getHour());
	if (theHour.length() == 1){
		theHour = "0" + Integer.toString(actualLogTime.getHour());
	}

	String theMinute = Integer.toString(actualLogTime.getMinute());
	if (theMinute.length() == 1) {
		theMinute = "0" + Integer.toString(actualLogTime.getMinute());
	}

	String theSecond = Integer.toString(actualLogTime.getSecond());
	if (theSecond.length() == 1) {
		theSecond = "0" + Integer.toString(actualLogTime.getSecond());
	}	

	String logTimeString = theHour + ":" + theMinute + ":" + theSecond + "    ";

	PrintWriter pw = new PrintWriter(new FileOutputStream(new File("log/" + logFile + ".log"), true));
	pw.println(logTimeString + message);
	pw.close();

}

private static String epochConverter(int epochTime) {

	LocalDateTime thetime = LocalDateTime.ofEpochSecond((long) epochTime, 0, ZoneOffset.of("Z"));
	ZonedDateTime instant = ZonedDateTime.of(thetime, ZoneId.of("GMT"));
	LocalDateTime t = instant.withZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime();

	String theHour = Integer.toString(t.getHour());
	if (theHour.length() == 1){
		theHour = "0" + Integer.toString(t.getHour());
	}

	String theMinute = Integer.toString(t.getMinute());
	if (theMinute.length() == 1) {
		theMinute = "0" + Integer.toString(t.getMinute());
	}

	String theSecond = Integer.toString(t.getSecond());
	if (theSecond.length() == 1) {
		theSecond = "0" + Integer.toString(t.getSecond());
	}	

	String logTimeString = theHour + ":" + theMinute + ":" + theSecond;
	return logTimeString;

}

/////////////////////////////////////////////////////////////////////////////////
//								Data accessing							  	   //
/////////////////////////////////////////////////////////////////////////////////
private static Track[] getAdverts() {

	try {
		ArrayList<Track> advertsToBeReturned = new ArrayList<Track>();

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT * FROM v_audio_adverts WHERE sustainer='t' ORDER BY RANDOM()");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			Track thisTrack = new Track(rs.getInt("id"), rs.getInt("length_smpl"), rs.getInt("start_smpl"), rs.getInt("end_smpl"), rs.getInt("intro_smpl"), rs.getInt("extro_smpl"));
			advertsToBeReturned.add(thisTrack);
		}
		rs.close();
		st.close();

		Track[] returnArray = new Track[advertsToBeReturned.size()];
		returnArray = advertsToBeReturned.toArray(returnArray);


		System.out.println("getAdverts: Returning val: " + returnArray[0].toString());

		return returnArray;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	// Something went completely wrong, return an empty array!
	Track[] emptyArray = new Track[0];
	return emptyArray; // Something went fatally wrong!
}



private static Track getRandomJingle() throws FileNotFoundException {

	try {
		Track jingleToReturn = new Track(-1);

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT * FROM v_audio_jingles WHERE enabled='t' ORDER BY RANDOM() LIMIT 1");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			jingleToReturn = new Track(rs.getInt("id"), rs.getInt("length_smpl"), rs.getInt("start_smpl"), rs.getInt("end_smpl"), rs.getInt("intro_smpl"), rs.getInt("extro_smpl"));
		}
		rs.close();
		st.close();

		System.out.println("...getRandomJingle: Returning val: " + jingleToReturn.getAudioID());
		logToFile("[AUDIO] Random jingle requested. Audio id of jingle: " + jingleToReturn.getAudioID() + ".");

		return jingleToReturn;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	Track emptyTrack = new Track(-1);

	return emptyTrack; // An error occurred
}



private static float getCurrentSongFinishTime(){

	try {
		int epoch = (int) (System.currentTimeMillis() / 1000L);
		float finishTime = epoch;

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT log.datetime AS dt, audio.length_smpl AS ls FROM log INNER JOIN audio ON log.audioid = audio.id WHERE log.location = 0 ORDER BY log.datetime DESC LIMIT 1");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			finishTime = rs.getInt("dt") + (rs.getInt("ls") / SAMPLE_RATE);
		}
		rs.close();
		st.close();

		return finishTime;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	return -1.0f; // An error occurred
}




private static void addAudioToSchedule(Track track) throws FileNotFoundException {

	System.out.println("addAudioToSchedule: Adding " + track.getAudioID());
	logToFile("[AUDIO] Track requested. Audio id of track: " + track.getAudioID() + ".");

	try {
		int playlistSize = -1;

		Statement st = databaseConn.createStatement();
		st.executeQuery("INSERT INTO sustschedule (audioid,start,trim_start_smpl,trim_end_smpl,fade_in,fade_out) VALUES (" + track.getAudioID() + ",0, " + track.getTrimStart() + ", " + track.getTrimEnd() + ",0,0)");
		
		
		st.close();

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

}




private static void clearSchedule() throws FileNotFoundException {

	try {

		Statement st = databaseConn.createStatement();
		st.executeQuery("TRUNCATE sustschedule");
		
		
		st.close();

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	logToFile("[AUDIO] Schedule clear requested. Now has " + getScheduleRemainingSize() + " tracks in queue.");

}




private static int getPlaylistSize(){

	try {
		int playlistSize = -1;

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT count(id) FROM audio WHERE sustainer ='t'");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			playlistSize = rs.getInt("count");
		}
		rs.close();
		st.close();

		return playlistSize;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	return -1; // An error occurred
}




private static float getPlaylistLength(){

	// NOTE - THIS CURRENTLY RETURNS LENGTH OF EVERYTHING IN THE AUDIO TABLE WITH SUSTAINER = T

	try {
		float playlistLength = -1.0f;

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT sum(length_smpl) FROM audio WHERE sustainer ='t'");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			playlistLength = rs.getFloat("sum");
		}
		rs.close();
		st.close();

		return playlistLength;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	return -1.0f; // An error occurred
}




private static int getScheduleRemainingSize(){

	try {
		int scheduleSize = -1;

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT count(id) FROM sustschedule");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			scheduleSize = rs.getInt("count");
		}
		rs.close();
		st.close();

		return scheduleSize;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	return -1; // An error occurred
	
}




private static int getSchedulerSecondsRemaining(){

	try {
		long secondsRemaining = -1;

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT sum(audio.length_smpl) FROM sustschedule INNER JOIN audio ON sustschedule.audioid = audio.id");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			secondsRemaining = rs.getLong("sum");
		}
		rs.close();
		st.close();

		if (secondsRemaining > Integer.MAX_VALUE) {
			logToFile("[ERROR] Integer overflow detected, defaulting to " + Integer.MAX_VALUE + ".");
			secondsRemaining = Integer.MAX_VALUE;
		}

		return (int) (secondsRemaining / SAMPLE_RATE);

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	return -1; // An error occurred

}




private static void printSchedule() throws FileNotFoundException {

	try {
		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT sustschedule.id, sustschedule.audioid AS audioid, audio.title AS title, artists.name AS artist FROM sustschedule INNER JOIN audio ON sustschedule.audioid = audio.id INNER JOIN audioartists ON audio.id = audioartists.audioid INNER JOIN artists ON audioartists.artistid = artists.id ORDER BY sustschedule.id ASC");

		System.out.println("    Title                                               Length       ID");
		System.out.println("----------------------------------------------------------------------------");
		logToFile("[SCHED] Begin schedule print.");
		logToFile("");

		while ( rs.next() ){
			String audioTitle = rs.getString("title");
			String audioArtist = rs.getString("artist");
			int audioID = rs.getInt("audioid");
			System.out.println(audioTitle + " (" + audioArtist + ") {" + audioID + "}");
			logToFile("        " + audioTitle + " (" + audioArtist + ") {" + audioID + "}");

		}
		rs.close();
		st.close();

		System.out.println("----------------------------------------------------------------------------");
		logToFile("");
		logToFile("[SCHED] End schedule print.");

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}
	
}




private static Track getPrerecordForTime(LocalDateTime timestamp){

	int day = timestamp.getDayOfWeek().getValue(); // 1 = mon .. 7 = sun
	int hour = timestamp.getHour();

	try {
		Track prerecordToReturn = new Track(-1);

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT sustslots.audioid, audio.length_smpl, audio.start_smpl, audio.end_smpl, audio.intro_smpl, audio.extro_smpl FROM sustslots INNER JOIN audio ON sustslots.audioid = audio.id WHERE day = " + day + " AND time = " + hour);
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			prerecordToReturn = new Track(rs.getInt("audioid"), rs.getInt("length_smpl"), rs.getInt("start_smpl"), rs.getInt("end_smpl"), rs.getInt("intro_smpl"), rs.getInt("extro_smpl"));
		}
		rs.close();
		st.close();

		return prerecordToReturn;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	return new Track(-1); // An error occurred
}




private static int getPlaylistForTime(LocalDateTime timestamp){

	int day = timestamp.getDayOfWeek().getValue(); // 1 = mon .. 7 = sun
	int hour = timestamp.getHour();

	try {
		int playlistToReturn = -1;

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT playlistid FROM sustslots WHERE day = " + day + " AND time = " + hour);
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			playlistToReturn = rs.getInt("playlistid");
		}
		rs.close();
		st.close();

		return playlistToReturn;

	} catch (SQLException exception){
		System.err.println("DIS IS THE ERROR BRUP: " + exception.getMessage());
	}

	return -1; // An error occurred
}




private static Track[] getSongsInPlaylist(int playlistID){

	try {
		ArrayList<Track> songsInPlaylist = new ArrayList<Track>();

		Statement st = databaseConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT audioplaylists.audioid, audio.length_smpl, audio.start_smpl, audio.end_smpl, audio.intro_smpl, audio.extro_smpl FROM playlists INNER JOIN audioplaylists ON playlists.id = audioplaylists.playlistid INNER JOIN audio ON audioplaylists.audioid = audio.id WHERE playlists.id = " + playlistID + " ORDER BY RANDOM()");
		
		while ( rs.next() ){
			// Add the audioid to the advert list
			Track thisTrack = new Track(rs.getInt("audioid"), rs.getInt("length_smpl"), rs.getInt("start_smpl"), rs.getInt("end_smpl"), rs.getInt("intro_smpl"), rs.getInt("extro_smpl"));
			songsInPlaylist.add(thisTrack);
		}
		rs.close();
		st.close();

		Track[] returnArray = new Track[songsInPlaylist.size()];
		returnArray = songsInPlaylist.toArray(returnArray);

		return returnArray;

	} catch (SQLException exception){
		System.err.println(exception.getMessage());
	}

	// Something went completely wrong, return an empty array!
	Track[] emptyArray = new Track[0];
	return emptyArray; // Something went fatally wrong!
}




//  if (t.bin != 5 && t_previous.bin != 5) {
// if (t.length_smpl > 441000 && t_previous.length_smpl > 441000)
// t.fade_in_smpl = t.trim_start_smpl + 220500;
// }
// if (t.bin != 5 && t_next.bin != 5) {
// if (t.length_smpl > 441000 && t_next.length_smpl > 441000)
// t.fade_out_smpl = t.trim_end_smpl - 220500;
// }



	

} // Ends the class

class Track {

	int audioid;
	int length_smpl;
	int trim_start_smpl;
	int trim_end_smpl;
	int intro_smpl;
	int extro_smpl;

	public Track(int audioid) {
		this.audioid = audioid;
	}

	public Track(int audioid, int length_smpl, int trim_start_smpl, int trim_end_smpl, int intro_smpl, int extro_smpl) {
		this.audioid = audioid;
		this.length_smpl = length_smpl;
		this.trim_start_smpl = trim_start_smpl;
		this.trim_end_smpl = trim_end_smpl;
		this.intro_smpl = intro_smpl;
		this.extro_smpl = extro_smpl;
	}

	public int getAudioID() { return this.audioid; }
	public int getLength() { return this.length_smpl; }
	public int getTrimStart() { return this.trim_start_smpl; }
	public int getTrimEnd() { return this.trim_end_smpl; }
	public int getIntro() { return this.intro_smpl; }
	public int getExtro() { return this.extro_smpl; }

}