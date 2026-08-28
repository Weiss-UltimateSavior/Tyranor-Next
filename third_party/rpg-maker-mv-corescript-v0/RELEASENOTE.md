# RPGMakerMV corescript "community-1.1" Release Notes

## Summary
The big change is "bug fix", "WebGL" and "preload". Especially in mobile devices, high speed and high quality games will be drawn by adopting WebGL mode.  In addition, the new preload system loads images to be used later in advance in the background (without freezing the game), so you can enjoy comfortably even if Internet is crowded.

## Features

- The memory management mechanism was introduced. We are no longer storing images above the upper limit of memory usage prescribed by the mechanism. This upper limit value is defined by `ImageCache.limit`, so you can change it with plugin as necessary.
- Image preload system added. At the start of each map, common, battle event, analyze the instruction of the event page and load the image to be used first. As the game operation does not stop while loading, it seems that loading is faster. This preload system can also be used from the plugin in the form of `ImageManager.requestXxxxx`.

## Changes

- WebGL mode is adopted in all environments. In the past, mobile devices forcibly started in canvas mode, but with the elimination of the above memory problem, WebGL mode can be used. WebGL is a browser's new drawing API, which enables drawing at a higher speed and quality than canvas mode. This will improve the speed of the game on the mobile devices. In addition, plugins using WebGL will also work on mobile devices.
- Improved font loading method. First, we extended the time limit of Font load error from 20 seconds to 60 seconds. Also, we adopted a mechanism called "CSS Font Loading" effective for some browsers. As a result, Font load error will not be displayed even if it takes more than 60 seconds to load fonts in the compatible browsers. Finally, the problem that Chrome mixed font of another game is solved.
- "Play Movie" has been changed. First, we fixed the problem that movies can not be played on Android. Next, we made sure that the event command does not advance until the movie ends. Also, movie playback on iPod / iPhone has become full screen so far, but we made it same inline system as other devices to unify user experience.
- Minimized the wait for loading images when "Show Text", "Show Animation", and "Change Tileset". These commands waited until all the images were loaded, but waiting for unrelated images to load was unnecessarily time consuming. So we modified to wait for only the images used in each command.

## Fixes

- Fix an error might occur when "Erase Picture".
- Fix saving during execution of parallel processing event may fail.
- Fix the display will be incorrect if you change `boxWidth` or `boxHeight`.
- Fix does not work in iOS8 Safari.
- Fix can not change the volume of BGM / BGS after playing the ME or after the battle ends.
- Fix animation of the second weapon was not flipped correctly.
- Fix sprite may not be displayed when adding it after windowLayer.
- Fix black lines are displayed on game screen with mac and iOS. (Fixed by updating to Pixi 4.4.1)

## Committers
liply, krmbn0576, wilfrem, ivanpopelyshev

## How to test
It is appreciated if you can test the changed points!  Especially change of image loading system (memory managemant and preloading) are major modifications, so it will be helpful if you can test with the game using lots of images!  Please use the bundled *Debug_ReportMemory.js* when testing! The size and number of the images being loaded is displayed in the upper right.  This number is constant as long as it operates normally. If it continues to increase it is bad. We are happy if you can report if a bad situation occurs.

# Trailer
First, load the image. Then.. Holy shit, Loading Error!  Like everything you do. This game is like your life. Begining a lot of games, but never finish it without any errors. And nobody loves you. Next time, "Farewell Image Loading Error, Hello Loading Retry!" Please look forward to!
