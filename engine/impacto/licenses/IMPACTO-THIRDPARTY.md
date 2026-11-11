# Third-party component index

**impacto contains third-party code at the following locations in the source distribution:**

* `src/audio/adxaudiostream.cpp`: based on [vgmstream](https://github.com/losnoco/vgmstream) ADX implementation
* `src/io/cpkarchive.cpp`: CRILAYLA decompression based on work by [tpu](https://forum.xentax.com/viewtopic.php?f=21&t=5137&hilit=CRILAYLA) and [hcs64/vgm_ripping/utf_tab](https://github.com/hcs64/vgm_ripping/tree/master/multi/utf_tab)
* `src/texture/bcdecode.cpp`: based on [bcndecode](https://github.com/ifeherva/bcndecode)
* `src/texture/bntxloader.cpp`: Unswizzling code based on [Ryujinx](https://github.com/Ryujinx/Ryujinx)
* `src/texture/gxtloader.cpp`: Unswizzling code based on [Scarlet](https://github.com/xdanieldzd/Scarlet/blob/d8aabf430307d35a81b131e40bb3c9a4828bdd7b/Scarlet/Drawing/ImageBinary.cs) and work by [FireyFly](http://xen.firefly.nu/up/rearrange.c.html) and [ryg](https://fgiesen.wordpress.com/2009/12/13/decoding-morton-codes/)
* `src/texture/s3tc.cpp`: based on [s3tc-dxt-decompression](https://github.com/Benjamin-Dobell/s3tc-dxt-decompression)
* `src/texture/ddsloader.cpp`: based on [OpenImageIO](https://github.com/OpenImageIO/oiio/tree/master/src/dds.imageio)
* `src/video/ffmpegplayer.cpp`: syncing code based on [FFplay](https://github.com/FFmpeg/FFmpeg/blob/master/fftools/ffplay.c)
* `vendor/clHCA`: part of [vgmstream](https://github.com/losnoco/vgmstream)
* `vendor/minilua`: [minilua](https://github.com/edubart/minilua)
* `vendor/glad`: output from [glad](https://github.com/Dav1dde/glad) generator, patched for Switch support
* `vendor/include/stb_image.h`: [stb_image](https://github.com/nothings/stb)
* `vendor/pcg`: [PCG Random Number Generation, Minimal C Edition](https://github.com/imneme/pcg-c-basic)
* `vendor/squish`: [Squish](http://sjbrown.co.uk/?code=squish)
* `vendor/mspack`: [libmspack](https://www.cabextract.org.uk/libmspack/), only includes LZX decompressor to reduce code size
* `vendor/mio`: [mio](https://github.com/vimpunk/mio), except for platforms without mmap support

All third-party code mentioned above is mandatory, included in the build process and compiled into the output executable for impacto on every supported platform and build configuration.

**Additionally, impacto depends on the following external libraries:**

* Platform implementations of C runtime, C++ STL, OpenAL and OpenGL (ES)
* [glm](https://github.com/g-truc/glm/)
* [LibAtrac9](https://github.com/Thealexbarney/LibAtrac9)
* [Ogg](https://github.com/xiph/ogg)
* [SDL3](https://libsdl.org/)
* [Vorbis](https://github.com/xiph/vorbis)
* [zlib](https://github.com/madler/zlib)
* [libass](https://github.com/libass/libass)
* [avcpp](https://github.com/h4tr3d/avcpp)
* [concurrent_queue](https://github.com/cameron314/concurrentqueue)
* [readerwriterqueue](https://github.com/cameron314/readerwriterqueue)
* [fmtlib](https://github.com/fmtlib/fmt)
* [pugixml](https://github.com/zeux/pugixml)
* [UTF8-CPP](https://github.com/nemtrif/utfcpp)
* [magic_enum](https://github.com/Neargye/magic_enum)
* [boost](https://boost.org)
* [toml11](https://toruniina.github.io/toml11/)
* [imgui](https://github.com/ocornut/imgui)

**Sourcing and linkage:**

* Refer to individual toolchain/platform documentation for standard library licenses and default external library sourcing behaviour.
* Emscripten, NX, Android builds use static linking for all other external libraries, other platforms use dynamic linking.
* Some external dependencies (due to being header only libraries) are always compiled into the output executable.
* The example Emscripten build process explicitly uses emscripten-ports packages for [Ogg](https://github.com/emscripten-ports/Ogg), SDL3, [Vorbis](https://github.com/emscripten-ports/Vorbis) and [zlib](https://github.com/emscripten-ports/zlib).
* Win32/MSVC, Linux, Android, Mac OS X build processes explicitly uses vcpkg packages listed in vcpkg.json, and the original authors' source distributions for other dependencies.
* Win32 install targets copy all required DLLs except the standard C/C++ runtime libraries to the output folder.

**Binary licensing:**

* Unless specified otherwise, impacto source and binaries are covered by [the ISC license](LICENSE) but contain and use the dependencies listed above, requiring third-party copyright notices.
* NX builds statically link OpenAL Soft (assuming this is used as the OpenAL implementation), placing NX binaries under LGPLv2 or later.
* Android builds statically link FFmpeg, placing those binaries under LGPLv2. To disable FFmpeg, use define -DIMPACTO_DISABLE_FFMPEG.
* All builds ship with libmspack in the impacto executable, placing it under LGPLv2 or later. If you wish to disable libmspack and get rid of LGPLv2 dependency, please use -DIMPACTO_DISABLE_MSPACK
  define.

License statements for third-party code in this repository (where given) and external libraries bundled by the build process in at least one supported configuration follow:

# Statements

## bcndecode

https://github.com/ifeherva/bcndecode/blob/5bc7043002d7b2485c857624f5ef6f55576ba8b4/src/bcndecode.c

>     decoder for DXTn-compressed data
>
>     Format documentation:
>       http://oss.sgi.com/projects/ogl-sample/registry/EXT/texture_compression_s3tc.txt
>
>     The contents of this file are in the public domain (CC0)
>     Full text of the CC0 license:
>       https://creativecommons.org/publicdomain/zero/1.0/
>
>     To test:
>       compile: gcc -Iinclude -DBCN_DECODER_TEST BcnDecode.c -o bcndecode
>       run: dd bs=1 skip=128 if=bc3_test.dds | ./bcndecode 256 256 3 1 > bc3_test.png

See below for license text.

## Magic Enum

https://github.com/Neargye/magic_enum

Copyright (c) 2019 - 2024 Daniil Goncharov

See below for license text(MIT)

## MiniLua

Copyright (c) 1994–2019 Lua.org, PUC-Rio.
Copyright (c) 2020-2023 Eduardo Bart (https://github.com/edubart).

See below for license text(MIT)

## Emscripten

https://github.com/emscripten-core/emscripten/tree/1.38.21

Emscripten is available under 2 licenses, the MIT license and the University of Illinois/NCSA Open Source License. See below for license texts.

Authors:

>     The following authors have all licensed their contributions to Emscripten
>     under the licensing terms detailed in LICENSE.
>
>     (Authors keep copyright of their contributions, of course; they just grant
>     a license to everyone to use it as detailed in LICENSE.)
>
>     * Alon Zakai <alonzakai@gmail.com> (copyright owned by Mozilla Foundation)
>     * Tim Dawborn <tim.dawborn@gmail.com>
>     * Max Shawabkeh <max99x@gmail.com>
>     * Sigmund Vik <sigmund_vik@yahoo.com>
>     * Jeff Terrace <jterrace@gmail.com>
>     * Benoit Tremblay <trembl.ben@gmail.com>
>     * Andreas Bergmeier <abergmeier@gmx.net>
>     * Ben Schwartz <bens@alum.mit.edu>
>     * David Claughton <dave@eclecticdave.com>
>     * David Yip <yipdw@member.fsf.org>
>     * Julien Hamaide <julien.hamaide@gmail.com>
>     * Ehsan Akhgari <ehsan.akhgari@gmail.com> (copyright owned by Mozilla Foundation)
>     * Adrian Taylor <adrian@macrobug.com>
>     * Richard Assar <richard.assar@gmail.com>
>     * Nathan Hammond <emscripten@nathanhammond.com>
>     * Behdad Esfahbod <behdad@behdad.org>
>     * David Benjamin <davidben@mit.edu>
>     * Pierre Renaux <pierre@talansoft.com>
>     * Brian Anderson <banderson@mozilla.com>
>     * Jon Bardin <diclophis@gmail.com>
>     * Jukka Jylänki <jujjyl@gmail.com>
>     * Aleksander Guryanov <caiiiycuk@gmail.com>
>     * Chad Austin <chad@chadaustin.me> (copyright owned by IMVU)
>     * nandhp <nandhp@gmail.com>
>     * YeZhongWen <linghuye2.0@gmail.com>
>     * Xingxing Pan <forandom@gmail.com>
>     * Justin Kerk <dopefishjustin@gmail.com>
>     * Andrea Bedini <andrea.bedini@gmail.com>
>     * James Pike <totoro.friend@chilon.net>
>     * Mokhtar Naamani <mokhtar.naamani@gmail.com>
>     * Benjamin Stover <benjamin.stover@gmail.com>
>     * Riccardo Magliocchetti <riccardo.magliocchetti@gmail.com>
>     * Janus Troelsen <janus.troelsen@stud.tu-darmstadt.de>
>     * Lars Schneider <lars.schneider@autodesk.com> (copyright owned by Autodesk, Inc.)
>     * Joel Martin <github@martintribe.org>
>     * Manuel Wellmann <manuel.wellmann@autodesk.com> (copyright owned by Autodesk, Inc.)
>     * Xuejie Xiao <xxuejie@gmail.com>
>     * Dominic Wong <dom@slowbunyip.org>
>     * Alan Kligman <alan.kligman@gmail.com> (copyright owned by Mozilla Foundation)
>     * Anthony Liot <wolfviking0@yahoo.com>
>     * Michael Riss <Michael.Riss@gmx.de>
>     * Jasper St. Pierre <jstpierre@mecheye.net>
>     * Manuel Schölling <manuel.schoelling@gmx.de>
>     * Bruce Mitchener, Jr. <bruce.mitchener@gmail.com>
>     * Michael Bishop <mbtyke@gmail.com>
>     * Roger Braun <roger@rogerbraun.net>
>     * Vladimir Vukicevic <vladimir@pobox.com> (copyright owned by Mozilla Foundation)
>     * Lorant Pinter <lorant.pinter@prezi.com>
>     * Tobias Doerffel <tobias.doerffel@gmail.com>
>     * Martin von Gagern <martin@von-gagern.net>
>     * Ting-Yuan Huang <thuang@mozilla.com>
>     * Joshua Granick <jgranick@blackberry.com>
>     * Felix H. Dahlke <fhd@ubercode.de>
>     * Éloi Rivard <azmeuk@gmail.com>
>     * Alexander Gladysh <ag@logiceditor.com>
>     * Arlo Breault <arlolra@gmail.com>
>     * Jacob Lee <artdent@gmail.com> (copyright owned by Google, Inc.)
>     * Joe Lee <jlee@imvu.com> (copyright owned by IMVU)
>     * Andy Friesen <andy@imvu.com> (copyright owned by IMVU)
>     * Bill Welden <bwelden@imvu.com> (copyright owned by IMVU)
>     * Michael Ey <mey@imvu.com> (copyright owned by IMVU)
>     * Llorens Marti Garcia <lgarcia@imvu.com> (copyright owned by IMVU)
>     * Jinsuck Kim <jkim@imvu.com> (copyright owned by IMVU)
>     * Todd Lee <tlee@imvu.com> (copyright owned by IMVU)
>     * Anthony Pesch <inolen@gmail.com>
>     * Robert Bragg <robert.bragg@intel.com> (copyright owned by Intel Corporation)
>     * Sylvestre Ledru <sylvestre@debian.org>
>     * Tom Fairfield <fairfield@cs.xu.edu>
>     * Anthony J. Thibault <ajt@hyperlogic.org>
>     * John Allwine <jallwine86@gmail.com>
>     * Martin Gerhardy <martin.gerhardy@gmail.com>
>     * James Gregory <jgregory@zynga.com> (copyright owned by Zynga, Inc.)
>     * Dan Gohman <sunfish@google.com> (copyright owned by Google, Inc.)
>     * Jeff Gilbert <jgilbert@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Frits Talbot <frits@metapathy.com>
>     * Onno Jongbloed <hey@onnoj.net>
>     * Jez Ng <me@jezng.com>
>     * Marc Feeley <mfeeley@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Ludovic Perrine <jazzzz@gmail.com>
>     * David Barksdale <david.barksdale@adcedosolutions.com>
>     * Manfred Manik Nerurkar <nerurkar*at*made-apps.biz> (copyright owned by MADE, GmbH)
>     * Joseph Gentle <me@josephg.com>
>     * Douglas T. Crosher <dtc-moz@scieneer.com> (copyright owned by Mozilla Foundation)
>     * Douglas T. Crosher <info@jsstats.com> (copyright owned by Scieneer Pty Ltd.)
>     * Soeren Balko <soeren.balko@gmail.com>
>     * Ryan Kelly (ryan@rfk.id.au)
>     * Michael Lelli <toadking@toadking.com>
>     * Yu Kobayashi <yukoba@accelart.jp>
>     * Pin Zhang <zhangpin04@gmail.com>
>     * Nick Bray <ncbray@chromium.org> (copyright owned by Google, Inc.)
>     * Aidan Hobson Sayers <aidanhs@cantab.net>
>     * Charlie Birks <admin@daftgames.net>
>     * Ranger Harke <ranger.harke@autodesk.com> (copyright owned by Autodesk, Inc.)
>     * Tobias Vrinssen <tobias@vrinssen.de>
>     * Patrick R. Martin <patrick.martin.r@gmail.com>
>     * Richard Quirk <richard.quirk@gmail.com>
>     * Marcos Scriven <marcos@scriven.org>
>     * Antoine Lambert <antoine.lambert33@gmail.com>
>     * Daniel Aquino <mr.danielaquino@gmail.com>
>     * Remi Papillie <remi.papillie@gmail.com>
>     * Fraser Adams <fraser.adams@blueyonder.co.uk>
>     * Michael Tirado <icetooth333@gmail.com>
>     * Ben Noordhuis <info@bnoordhuis.nl>
>     * Bob Roberts <bobroberts177@gmail.com>
>     * John Vilk <jvilk@cs.umass.edu>
>     * Daniel Baulig <dbaulig@fb.com> (copyright owned by Facebook, Inc.)
>     * Lu Wang <coolwanglu@gmail.com>
>     * Heidi Pan <heidi.pan@intel.com> (copyright owned by Intel)
>     * Vasilis Kalintiris <ehostunreach@gmail.com>
>     * Adam C. Clifton <adam@hulkamaniac.com>
>     * Volo Zyko <volo.zyko@gmail.com>
>     * Andre Weissflog <floooh@gmail.com>
>     * Alexandre Perrot <alexandre.perrot@gmail.com>
>     * Emerson José Silveira da Costa <emerson.costa@gmail.com>
>     * Jari Vetoniemi <mailroxas@gmail.com>
>     * Sindre Sorhus <sindresorhus@gmail.com>
>     * James S Urquhart <jamesu@gmail.com>
>     * Boris Gjenero <boris.gjenero@gmail.com>
>     * jonas echterhoff <jonas@unity3d.com>
>     * Sami Vaarala <sami.vaarala@iki.fi>
>     * Jack A. Arrington <jack@epicpineapple.com>
>     * Richard Janicek <r@janicek.co>
>     * Joel Croteau <jcroteau@gmail.com>
>     * Haneef Mubarak <haneef503@gmail.com>
>     * Nicolas Peri <nicox@shivaengine.com> (copyright owned by ShiVa Technologies, SAS)
>     * Bernhard Fey <e-male@web.de>
>     * Dave Nicponski <dave.nicponski@gmail.com>
>     * Jonathan Jarri <noxalus@gmail.com>
>     * Daniele Di Proietto <daniele.di.proietto@gmail.com>
>     * Dan Dascalescu <dNOSPAMdascalescu@gmail.com>
>     * Thomas Borsos <thomasborsos@gmail.com>
>     * Ori Avtalion <ori@avtalion.name>
>     * Guillaume Blanc <guillaumeblanc.sc@gmail.com>
>     * Usagi Ito <usagi@WonderRabbitProject.net>
>     * Camilo Polymeris <cpolymeris@gmail.com>
>     * Markus Henschel <markus.henschel@yager.de>
>     * Ophir Lojkine <ophir.lojkine@eleves.ec-nantes.fr>
>     * Ryan Sturgell <ryan.sturgell@gmail.com> (copyright owned by Google, Inc.)
>     * Jason Green <jason@transgaming.com> (copyright owned by TransGaming, Inc.)
>     * Ningxin Hu <ningxin.hu@intel.com> (copyright owned by Intel)
>     * Nicolas Guillemot <nlguillemot@gmail.com>
>     * Sathyanarayanan Gunasekaran <gsathya.ceg@gmail.com> (copyright owned by Mozilla Foundation)
>     * Nikolay Vorobyov <nik.vorobyov@gmail.com>
>     * Jonas Platte <mail@jonasplatte.de>
>     * Sebastien Ronsse <sronsse@gmail.com>
>     * Glenn R. Wichman <gwichman@zynga.com>
>     * Hamish Willee <hamishwillee@gmail.com> (copyright owned by Mozilla Foundation)
>     * Sylvain Chevalier <sylvain.chevalier@gmail.com>
>     * Nathan Ross <nross.se@gmail.com>
>     * Zachary Pomerantz <zmp@umich.edu>
>     * Boris Tsarev <boristsarev@gmail.com>
>     * Mark Logan <mark@artillery.com> (copyright owned by Artillery Games, Inc.)
>     * Коренберг Марк <socketpair@gmail.com>
>     * Gauthier Billot <gogoprog@gmail.com>
>     * Árpád Goretity <h2co3@h2co3.org>
>     * Nicholas Wilson <nicholas@nicholaswilson.me.uk>
>     * Aaron Mandle <aaronmandle@gmail.com>
>     * Bailey Hayes <Bailey.Hayes@sas.com> (copyright owned by SAS Institute Inc.)
>     * Paul Holland <pholland@adobe.com>
>     * James Long <longster@gmail.com>
>     * David Anderson <danderson@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Eric Rannaud <e@nanocritical.com> (copyright owned by Nanocritical Corp.)
>     * William Furr <wfurr@google.com> (copyright owned by Google, Inc.)
>     * Dan Glastonbury <dglastonbury@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Warren Seine <warren.seine@aerys.in> (copyright owned by Aerys SAS)
>     * Petr Babicka <babcca@gmail.com>
>     * Akira Takahashi <faithandbrave@gmail.com>
>     * Victor Costan <costan@gmail.com>
>     * Pepijn Van Eeckhoudt <pepijn.vaneeckhoudt@luciad.com> (copyright owned by Luciad NV)
>     * Stevie Trujillo <stevie.trujillo@gmail.com>
>     * Edward Rudd <urkle@outoforder.cc>
>     * Rene Eichhorn <rene.eichhorn1@gmail.com>
>     * Nick Desaulniers <nick@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Luke Wagner <luke@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Matt McCormick <matt.mccormick@kitware.com>
>     * Thaddée Tyl <thaddee.tyl@gmail.com>
>     * Philipp Wiesemann <philipp.wiesemann@arcor.de>
>     * Jan Jongboom <janjongboom@gmail.com> (copyright owned by Telenor Digital AS)
>     * Tiago Quelhas <tiagoq@gmail.com>
>     * Reinier de Blois <rddeblois@gmail.com>
>     * Yuichi Nishiwaki <yuichi.nishiwaki@gmail.com>
>     * Jérôme Bernard <jerome.bernard@ercom.fr> (copyright owned by Ercom)
>     * Chanhwi Choi <ccwpc@hanmail.net>
>     * Fábio Santos <fabiosantosart@gmail.com>
>     * Thibaut Despoulain <thibaut@artillery.com> (copyright owned by Artillery Games, Inc.)
>     * Wei Tjong Yao <weitjong@gmail.com>
>     * Tim Guan-tin Chien <timdream@gmail.com>
>     * Krzysztof Jakubowski <nadult@fastmail.fm>
>     * Vladimír Vondruš <mosra@centrum.cz>
>     * Brion Vibber <brion@pobox.com>
>     * Philip Lafleur <sendsbeak@gmail.com>
>     * Javier Meseguer de Paz <j.meseguer@gmail.com>
>     * Michael A. Balazs <michael.balazs@gmail.com>
>     * Andreas Blixt <me@blixt.nyc>
>     * Haofeng Zhang <h.z@duke.edu>
>     * Cody Welsh <codyw@protonmail.com>
>     * Hoong Ern Ng <hoongern@gmail.com>
>     * Kagami Hiiragi <kagami@genshiken.org>
>     * Jan Bölsche <jan@lagomorph.de>
>     * Sebastian Matthes <sebastianmatthes@outlook.com> (copyright owned by Volkswagen AG)
>     * Robert Goulet <robert.goulet@autodesk.com> (copyright owned by Autodesk, Inc.)
>     * Juha Järvi <befunge@gmail.com>
>     * Louis Lagrange <lagrange.louis@gmail.com>
>     * Ying-Ruei Liang <thumbd03803@gmail.com>
>     * Stuart Geipel <lapimlu@gmail.com>
>     * Yeonjun Lim <yjroot@gmail.com>
>     * Andrew Karpushin <reven86@gmail.com>
>     * Felix Zimmermann <fzimmermann89@gmail.com>
>     * Sven-Hendrik Haase <svenstaro@gmail.com>
>     * Simon Sandström <simon@nikanor.nu>
>     * Khaled Sami <k.sami.mohammed@gmail.com>
>     * Omar El-Mohandes <omar.elmohandes90@gmail.com>
>     * Florian Rival <florian.rival@gmail.com>
>     * Mark Achée <mark@achee.com>
>     * Piotr Paczkowski <kontakt@trzeci.eu>
>     * Braden MacDonald <braden@bradenmacdonald.com>
>     * Kevin Cheung <kevin.cheung@autodesk.com> (copyright owned by Autodesk, Inc.)
>     * Josh Peterson <petersonjm1@gmail.com>
>     * eska <eska@eska.me>
>     * Nate Burr <nate.oo@gmail.com>
>     * Paul "TBBle" Hampson <Paul.Hampson@Pobox.com>
>     * Andreas Plesch <andreasplesch@gmail.com>
>     * Brian Armstrong <brian.armstrong.ece+github@gmail.com>
>     * Vincenzo Chianese <vincenz.chianese@icloud.com>
>     * Noam T.Cohen <noam@ecb.co.il>
>     * Nick Shin <nick.shin@gmail.com>
>     * Gregg Tavares <github@greggman.com>
>     * Tanner Rogalsky <tanner@tannerrogalsky.com>
>     * Richard Cook <rcook@tableau.com> (copyright owned by Tableau Software, Inc.)
>     * Arnab Choudhury <achoudhury@tableau.com> (copyright owned by Tableau Software, Inc.)
>     * Charles Vaughn <cvaughn@tableau.com> (copyright owned by Tableau Software, Inc.)
>     * Pierre Krieger <pierre.krieger1708@gmail.com>
>     * Jakob Stoklund Olesen <stoklund@2pi.dk>
>     * Jérémy Anger <angerj.dev@gmail.com>
>     * Derek Schuff <dschuff@chromium.org> (copyright owned by Google, Inc.)
>     * Ashley Sommer <flubba86@gmail.com>
>     * Dave Fletcher <graveyhead@gmail.com>
>     * Lars-Magnus Skog <ralphtheninja@riseup.net>
>     * Pieter Vantorre <pietervantorre@gmail.com>
>     * Maher Sallam <maher@sallam.me>
>     * Andrey Burov <burik666@gmail.com>
>     * Holland Schutte <hgschutte1@gmail.com>
>     * Kerby Geffrard <kerby.geffrard@gmail.com>
>     * cynecx <me@cynecx.net>
>     * Chris Gibson <cgibson@mrvoxel.com>
>     * Harald Reingruber <code*at*h-reingruber.at>
>     * Aiden Koss <madd0131@umn.edu>
>     * Dustin VanLerberghe <good_ol_dv@hotmail.com>
>     * Philip Bielby <pmb45-github@srcf.ucam.org> (copyright owned by Jagex Ltd.)
>     * Régis Fénéon <regis.feneon@gmail.com>
>     * Dominic Chen <d.c.ddcc@gmail.com> (copyright owned by Google, Inc.)
>     * Junji Hashimoto <junji.hashimoto@gmail.com>
>     * Heejin Ahn <aheejin@gmail.com> (copyright owned by Google, Inc.)
>     * Andras Kucsma <andras.kucsma@gmail.com>
>     * Mateusz Borycki <mateuszborycki@gmail.com>
>     * Franklin Ta <fta2012@gmail.com>
>     * Jacob Gravelle <jgravelle@google.com> (copyright owned by Google, Inc.)
>     * Kagami Sascha Rosylight <saschanaz@outlook.com>
>     * Benny Jacobs <benny@gmx.it>
>     * Ray Brown <code@liquibits.com>
>     * Christopher Serr <christopher.serr@gmail.com>
>     * Aaron Ruß <aaron.russ@dfki.de> (copyright owned by DFKI GmbH)
>     * Vilibald Wanča <vilibald@wvi.cz>
>     * Alex Hixon <alex@alexhixon.com>
>     * Vladimir Davidovich <thy.ringo@gmail.com>
>     * Yuriy Levchenko <irov13@mail.ru>
>     * Dmitry Tolmachov <dmitolm@gmail.com>
>     * Dylan McKay <me@dylanmckay.io>
>     * Christophe Gragnic <cgragnic@netc.fr>
>     * Murphy McCauley <murphy.mccauley@gmail.com>
>     * Anatoly Trosinenko <anatoly.trosinenko@gmail.com>
>     * Brad Grantham <grantham@plunk.org>
>     * Sam Clegg <sbc@chromium.org> (copyright owned by Google, Inc.)
>     * Joshua Lind <joshualind007@hotmail.com>
>     * Hiroaki GOTO as "GORRY" <gorry@hauN.org>
>     * Mikhail Kremnyov <mkremnyov@gmail.com> (copyright owned by XCDS International)
>     * Tasuku SUENAGA a.k.a. gunyarakun <tasuku-s-github@titech.ac>
>     * Vitorio Miguel Prieto Cilia <vdrbandeiras@gmail.com>
>     * Evan Wallace <evan.exe@gmail.com>
>     * Henning Pohl <henning@still-hidden.de>
>     * Tim Neumann <mail@timnn.me>
>     * Ondrej Stava <ondrej.stava@gmail.com> (copyright owned by Google, Inc.)
>     * Jakub Jirutka <jakub@jirutka.cz>
>     * Loo Rong Jie <loorongjie@gmail.com>
>     * Jean-François Geyelin <jfgeyelin@gmail.com>
>     * Matthew Collins <thethinkofdeath@gmail.com>
>     * Satoshi N. M <snmatsutake@yahoo.co.jp>
>     * Ryan Speets <ryan@speets.ca>
>     * Fumiya Chiba <fumiya.chiba@nifty.com>
>     * Ryan C. Gordon <icculus@icculus.org>
>     * Inseok Lee <dlunch@gmail.com>
>     * Yair Levinson (copyright owned by Autodesk, Inc.)
>     * Matjaž Drolc <mdrolc@gmail.com>
>     * James Swift <james@3dengineer.com> (copyright owned by PSPDFKit GmbH)
>     * Ryan Lester <ryan@cyph.com> (copyright owned by Cyph, Inc.)
>     * Nikolay Zapolnov <zapolnov@gmail.com>
>     * Nazar Mokrynskyi <nazar@mokrynskyi.com>
>     * Yury Delendik <ydelendik@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Kenneth Perry <thothonegan@gmail.com>
>     * Jim Mussared <jim.mussared@gmail.com>
>     * Dirk Vanden Boer <dirk.vdb@gmail.com>
>     * Mitchell Foley <mitchfoley@google.com> (copyright owned by Google, Inc.)
>     * Oleksandr Chekhovskyi <oleksandr.chekhovskyi@gmail.com>
>     * Michael Siebert <michael.siebert2k@gmail.com>
>     * Jonathan Hale <squareys@googlemail.com>
>     * Etienne Brateau <etienne.brateau@gmail.com>
>     * Zhiming Wang <zmwangx@gmail.com>
>     * Jameson Ernst <jameson@jpernst.com>
>     * Yoan Lecoq <yoanlecoq.io@gmail.com>
>     * Jiajie Hu <jiajie.hu@intel.com> (copyright owned by Intel Corporation)
>     * Kamil Klimek <naresh@tlen.pl>
>     * José Carlos Pujol <josecpujol(at)gmail.com>
>     * Dannii Willis <curiousdannii@gmail.com>
>     * Erik Dubbelboer <erik@dubbelboer.com>
>     * Sergey Tsatsulin <tsatsulin@gmail.com>
>     * varkor <github@varkor.com>
>     * Stuart Knightley <website@stuartk.com>
>     * Amadeus Guo<gliheng@foxmail.com>
>     * Nathan Froyd <froydnj@gmail.com> (copyright owned by Mozilla Foundation)
>     * Daniel Wirtz <dcode@dcode.io>
>     * Kibeom Kim <kk1674@nyu.edu>
>     * Marcel Klammer <m.klammer@tastenkunst.com>
>     * Axel Forsman <axelsfor@gmail.com>
>     * Ebrahim Byagowi <ebrahim@gnu.org>
>     * Thorsten Möller <thorsten.moeller@sbi.ch>
>     * Michael Droettboom <mdroettboom@mozilla.com>
>     * Nicolas Bouquet <hgy01@hieroglyphe.net>
>     * Miguel Saldivar <miguel.saldivar22@hotmail.com>
>     * Gert Van Hoey <gert.vanhoey@gmail.com>
>     * Valtteri Heikkilä <rnd@nic.fi>
>     * Daniel McNab <daniel.mcnab6+emcc(at)gmail.com>
>     * Tyler Limkemann <tslimkemann42 gmail.com>
>     * Ben Smith <binji@google.com> (copyright owned by Google, Inc.)
>     * Sylvain Beucler <beuc@beuc.net>
>     * Patrik Weiskircher <patrik@weiskircher.name>
>     * Tobias Widlund <widlundtobias(at)gmail.com>
>     * Rob Fors <mail@robfors.com>
>     * Mike Frysinger <vapier@chromium.org> (copyright owned by Google, Inc.)
>     * Sébasiten Crozet <developer@crozet.re>
>     * Andrey Nagikh <andrey@nagih.ru>
>     * Dzmitry Malyshau <dmalyshau@mozilla.com> (copyright owned by Mozilla Foundation)
>     * Bjorn Swenson <tie.372@gmail.com>
>     * Ryhor Spivak <grisha@rusteddreams.net>
>     * Jan Schär <jscissr@gmail.com>
>     * Ryhor Spivak <grisha@rusteddreams.net>
>     * Alexander Bich <quyse0@gmail.com>
>     * Ashleigh Thomas <ashleighbcthomas@gmail.com>
>     * Veniamin Petrenko <bjpbjpbjp10@gmail.com>
>     * Ian Henderson <ian@ianhenderson.org>
>     * Siim Kallas <siimkallas@gmail.com>
>     * Carl Woffenden <cwoffenden@gmail.com> (copyright owned by Numfum GmbH)
>     * Patrick Berger <patrick.berger@xmail.net> (copyright owned by Compusoft Group)
>     * Alexander Frank Lehmann <alexander.frank.lehmann@compusoftgroup.com> (copyright owned by Compusoft Group)
>     * Tommy Nguyen <tn0502@gmail.com>
>     * Thomas Schander <info@thomasschander.com> (copyright owned by Enscape GmbH)
>     * Benjamin S. Rodgers <acdimalev@gmail.com>
>     * Paul Shapiro <paul@mymonero.com>
>     * Elmo Todurov <elmo.todurov@eesti.ee>
>     * Zoltán Žarkov <zeko@freecivweb.org>
>     * Roman Yurchak <rth.yurchak@pm.me>
>     * Hampton Maxwell <me@hamptonmaxwell.com>
>     * Eric Fiselier <ericwf@google.com> (copyright owned by Google, Inc.)
>     * Sirapop Wongstapornpat <sirapop.wongstapornpat@student.oulu.fi>
>     * Matt Kane <m@mk.gg>
>     * Altan Özlü <altanozlu7@gmail.com>
>     * Mary S <ipadlover8322@gmail.com>
>     * Martin Birks <mbirks@gmail.com>
>     * Kirill Smelkov <kirr@nexedi.com> (copyright owned by Nexedi)
>     * Lutz Hören <laitch383@gmail.com>
>     * Pedro K Custodio <git@pedrokcustodio.com>

Emscripten dependencies:

>     This program uses portions of Node.js source code located in src/library_path.js,
>     in accordance with the terms of the MIT license. Node's license follows:

(See below for license text. node.js is "Copyright Joyent, Inc. and other Node contributors. All rights reserved.")

>     The musl libc project is bundled in this repo, and it has the MIT license, see

Emscripten 1.38.21 / musl 1.1.15 system/lib/libc/musl/COPYRIGHT:

>     musl as a whole is licensed under the following standard MIT license:
>
>     ----------------------------------------------------------------------
>     Copyright © 2005-2014 Rich Felker, et al.
>
>     Permission is hereby granted, free of charge, to any person obtaining
>     a copy of this software and associated documentation files (the
>     "Software"), to deal in the Software without restriction, including
>     without limitation the rights to use, copy, modify, merge, publish,
>     distribute, sublicense, and/or sell copies of the Software, and to
>     permit persons to whom the Software is furnished to do so, subject to
>     the following conditions:
>
>     The above copyright notice and this permission notice shall be
>     included in all copies or substantial portions of the Software.
>
>     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
>     EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
>     MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
>     IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
>     CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
>     TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
>     SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
>     ----------------------------------------------------------------------
>
>     Authors/contributors include:
>
>     Alex Dowad
>     Alexander Monakov
>     Anthony G. Basile
>     Arvid Picciani
>     Bobby Bingham
>     Boris Brezillon
>     Brent Cook
>     Chris Spiegel
>     Clément Vasseur
>     Daniel Micay
>     Denys Vlasenko
>     Emil Renner Berthing
>     Felix Fietkau
>     Felix Janda
>     Gianluca Anzolin
>     Hauke Mehrtens
>     Hiltjo Posthuma
>     Isaac Dunham
>     Jaydeep Patil
>     Jens Gustedt
>     Jeremy Huntwork
>     Jo-Philipp Wich
>     Joakim Sindholt
>     John Spencer
>     Josiah Worcester
>     Justin Cormack
>     Khem Raj
>     Kylie McClain
>     Luca Barbato
>     Luka Perkov
>     M Farkas-Dyck (Strake)
>     Mahesh Bodapati
>     Michael Forney
>     Natanael Copa
>     Nicholas J. Kain
>     orc
>     Pascal Cuoq
>     Petr Hosek
>     Pierre Carrier
>     Rich Felker
>     Richard Pennington
>     Shiz
>     sin
>     Solar Designer
>     Stefan Kristiansson
>     Szabolcs Nagy
>     Timo Teräs
>     Trutz Behn
>     Valentin Ochs
>     William Haddon
>
>     Portions of this software are derived from third-party works licensed
>     under terms compatible with the above MIT license:
>
>     The TRE regular expression implementation (src/regex/reg* and
>     src/regex/tre*) is Copyright © 2001-2008 Ville Laurikari and licensed
>     under a 2-clause BSD license (license text in the source files). The
>     included version has been heavily modified by Rich Felker in 2012, in
>     the interests of size, simplicity, and namespace cleanliness.
>
>     Much of the math library code (src/math/* and src/complex/*) is
>     Copyright © 1993,2004 Sun Microsystems or
>     Copyright © 2003-2011 David Schultz or
>     Copyright © 2003-2009 Steven G. Kargl or
>     Copyright © 2003-2009 Bruce D. Evans or
>     Copyright © 2008 Stephen L. Moshier
>     and labelled as such in comments in the individual source files. All
>     have been licensed under extremely permissive terms.
>
>     The ARM memcpy code (src/string/arm/memcpy_el.S) is Copyright © 2008
>     The Android Open Source Project and is licensed under a two-clause BSD
>     license. It was taken from Bionic libc, used on Android.
>
>     The implementation of DES for crypt (src/crypt/crypt_des.c) is
>     Copyright © 1994 David Burren. It is licensed under a BSD license.
>
>     The implementation of blowfish crypt (src/crypt/crypt_blowfish.c) was
>     originally written by Solar Designer and placed into the public
>     domain. The code also comes with a fallback permissive license for use
>     in jurisdictions that may not recognize the public domain.
>
>     The smoothsort implementation (src/stdlib/qsort.c) is Copyright © 2011
>     Valentin Ochs and is licensed under an MIT-style license.
>
>     The BSD PRNG implementation (src/prng/random.c) and XSI search API
>     (src/search/*.c) functions are Copyright © 2011 Szabolcs Nagy and
>     licensed under following terms: "Permission to use, copy, modify,
>     and/or distribute this code for any purpose with or without fee is
>     hereby granted. There is no warranty."
>
>     The x86_64 port was written by Nicholas J. Kain and is licensed under
>     the standard MIT terms.
>
>     The mips and microblaze ports were originally written by Richard
>     Pennington for use in the ellcc project. The original code was adapted
>     by Rich Felker for build system and code conventions during upstream
>     integration. It is licensed under the standard MIT terms.
>
>     The mips64 port was contributed by Imagination Technologies and is
>     licensed under the standard MIT terms.
>
>     The powerpc port was also originally written by Richard Pennington,
>     and later supplemented and integrated by John Spencer. It is licensed
>     under the standard MIT terms.
>
>     All other files which have no copyright comments are original works
>     produced specifically for use as part of this library, written either
>     by Rich Felker, the main author of the library, or by one or more
>     contibutors listed above. Details on authorship of individual files
>     can be found in the git version control history of the project. The
>     omission of copyright and license comments in each file is in the
>     interest of source tree size.
>
>     In addition, permission is hereby granted for all public header files
>     (include/* and arch/*/bits/*) and crt files intended to be linked into
>     applications (crt/*, ldso/dlstart.c, and arch/*/crt_arch.h) to omit
>     the copyright notice and permission notice otherwise required by the
>     license, and to use these files without any requirement of
>     attribution. These files include substantial contributions from:
>
>     Bobby Bingham
>     John Spencer
>     Nicholas J. Kain
>     Rich Felker
>     Richard Pennington
>     Stefan Kristiansson
>     Szabolcs Nagy
>
>     all of whom have explicitly granted such permission.
>
>     This file previously contained text expressing a belief that most of
>     the files covered by the above exception were sufficiently trivial not
>     to be subject to copyright, resulting in confusion over whether it
>     negated the permissions granted in the license. In the spirit of
>     permissive licensing, and of not having licensing issues being an
>     obstacle to adoption, that text has been removed.

## unordered_dense

https://github.com/martinus/unordered_dense

Copyright (c) 2022 Martin Leitner-Ankerl
See below for license text (MIT).

## glad

Generated with https://github.com/Dav1dde/glad/tree/v0.1.27

>     What's the license of glad generated code? #101
>     Any of Public Domain, WTFPL or CC0.

See below for license text (CC0).

## glm

https://github.com/g-truc/glm/tree/0.9.9.3 or system version

Copyright (c) 2005 - G-Truc Creation. GLM can be distributed and/or modified under the terms of either a) The Happy Bunny License, or b) the MIT License. See below for license text (MIT).

## LibAtrac9

https://github.com/Thealexbarney/LibAtrac9 or system version

Copyright (c) 2018 Alex Barney. See below for license text (MIT).

## Dear ImGui

https://github.com/ocornut/imgui

Copyright (c) 2014-2024 Omar Cornut. See below for license text (MIT).

## Ogg

system version, originally https://github.com/xiph/ogg

>     Copyright (c) 2002, Xiph.org Foundation
>
>     Redistribution and use in source and binary forms, with or without
>     modification, are permitted provided that the following conditions
>     are met:
>
>     - Redistributions of source code must retain the above copyright
>     notice, this list of conditions and the following disclaimer.
>
>     - Redistributions in binary form must reproduce the above copyright
>     notice, this list of conditions and the following disclaimer in the
>     documentation and/or other materials provided with the distribution.
>
>     - Neither the name of the Xiph.org Foundation nor the names of its
>     contributors may be used to endorse or promote products derived from
>     this software without specific prior written permission.
>
>     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
>     ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
>     LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
>     A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION
>     OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
>     SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
>     LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
>     DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
>     THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
>     (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
>     OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## OpenAL Soft

system version, originally https://github.com/kcat/openal-soft

>     OpenAL cross platform audio library
>     Copyright (C) 1999-2007 by authors.
>     This library is free software; you can redistribute it and/or
>      modify it under the terms of the GNU Library General Public
>      License as published by the Free Software Foundation; either
>      version 2 of the License, or (at your option) any later version.
>
>     This library is distributed in the hope that it will be useful,
>      but WITHOUT ANY WARRANTY; without even the implied warranty of
>      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
>      Library General Public License for more details.
>
>     You should have received a copy of the GNU Library General Public
>      License along with this library; if not, write to the
>      Free Software Foundation, Inc.,
>      51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
>     Or go to http://www.gnu.org/copyleft/lgpl.html

See below for license text (LGPLv2).

## OpenImageIO

https://github.com/OpenImageIO/oiio/blob/master/LICENSE.md

Copyright (c) 2008-present by Contributors to the OpenImageIO project.
All Rights Reserved.

See below for license text (BSD 3-Clause License).

## PCG Random Number Generation, Minimal C Edition

https://github.com/imneme/pcg-c-basic/tree/bc39cd76ac3d541e618606bcc6e1e5ba5e5e6aa3

Copyright 2014 Melissa O'Neill <oneill@pcg-random.org>

Licensed under the Apache License, Version 2.0. See below for license text.

## Ryujinx

https://github.com/Ryujinx/Ryujinx/tree/eee639d6ba544fa5dd9352426d55e91bc54e157d/Ryujinx.Graphics/Graphics3d/Texture

Copyright (c) Ryujinx Team and Contributors.

See below for license text (MIT).

## s3tc-dxt-decompression

https://github.com/Benjamin-Dobell/s3tc-dxt-decompression/blob/5f923ecc1553aa60058d7011360cefca73e8b0fd/s3tc.cpp

Copyright (c) 2009 Benjamin Dobell, Glass Echidna.

See below for license text (MIT).

## Scarlet

https://github.com/xdanieldzd/Scarlet/blob/d8aabf430307d35a81b131e40bb3c9a4828bdd7b/Scarlet/Drawing/ImageBinary.cs

Copyright (c) 2016 xdaniel (Daniel R.) / DigitalZero Domain

See below for license text (MIT).

## SDL3

https://libsdl.org/

Copyright (C) 1997-2025 Sam Lantinga <slouken@libsdl.org>

See below for license text (zlib).

## mio

https://github.com/vimpunk/mio/blob/master/single_include/mio/mio.hpp

Copyright (c) 2018 https://github.com/mandreyel/

See below for license text (MIT).

## stb_image

https://github.com/nothings/stb/blob/5d90a8375a1393abc0dc5f8cc9f1ac9fd9ae6530/stb_image.h

>     ------------------------------------------------------------------------------
>     This software is available under 2 licenses -- choose whichever you prefer.
>     ------------------------------------------------------------------------------
>     ALTERNATIVE A - MIT License
>     Copyright (c) 2017 Sean Barrett

See below for license text.

## Squish

https://github.com/OpenImageIO/oiio/blob/master/src/dds.imageio/squish/README

>     The squish library is distributed under the terms and conditions of the MIT
>     license. This license is specified at the top of each source file and must be
>     preserved in its entirety.

See below for license text.

## vgm_ripping

https://github.com/hcs64/vgm_ripping/tree/f460d548859d59dd03b2c3f29ed97e2dbf9bb5b3/multi/utf_tab

Copyright (c) 2014 Adam Gashlin.

See below for license text (MIT).

## vgmstream

https://github.com/losnoco/vgmstream/tree/ec0043bf6b816c817ee786458f314f2808fd5846

>     Copyright (c) 2008-2010 Adam Gashlin, Fastelbja, Ronny Elfert
>
>     Portions Copyright (c) 2004-2008, Marko Kreen
>     Portions Copyright 2001-2007  jagarl / Kazunori Ueno <jagarl@creator.club.ne.jp>
>     Portions Copyright (c) 1998, Justin Frankel/Nullsoft Inc.
>     Portions Copyright (C) 2006 Nullsoft, Inc.
>     Portions Copyright (c) 2005-2007 Paul Hsieh
>     Portions Public Domain originating with Sun Microsystems

See below for license text (ISC).

## Vorbis

system version, originally https://github.com/xiph/vorbis

>     Copyright (c) 2002-2018 Xiph.org Foundation
>
>     Redistribution and use in source and binary forms, with or without
>     modification, are permitted provided that the following conditions
>     are met:
>
>     - Redistributions of source code must retain the above copyright
>     notice, this list of conditions and the following disclaimer.
>
>     - Redistributions in binary form must reproduce the above copyright
>     notice, this list of conditions and the following disclaimer in the
>     documentation and/or other materials provided with the distribution.
>
>     - Neither the name of the Xiph.org Foundation nor the names of its
>     contributors may be used to endorse or promote products derived from
>     this software without specific prior written permission.
>
>     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
>     ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
>     LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
>     A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION
>     OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
>     SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
>     LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
>     DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
>     THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
>     (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
>     OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## zlib

system version, originally https://www.zlib.net/

Copyright (C) 1995-2017 Jean-loup Gailly and Mark Adler

See below for license text (zlib).

## FFmpeg

https://www.ffmpeg.org/

See below for license text (LGPLv2).

## UTF8-CPP

https://github.com/nemtrif/utfcpp

See below for license text (BSL-1.0)

## avcpp

Copyright (c) 2013-2017, Alexander Drozdov <adrozdoff@gmail.com>
All rights reserved.

See below for license text (BSD 3-Clause License).

Copyright 2006 Nemanja Trifunovic

>     Permission is hereby granted, free of charge, to any person or organization
>     obtaining a copy of the software and accompanying documentation covered by
>     this license (the "Software") to use, reproduce, display, distribute,
>     execute, and transmit the Software, and to prepare derivative works of the
>     Software, and to permit third-parties to whom the Software is furnished to
>     do so, all subject to the following:
>     
>     The copyright notices in the Software and this entire statement, including
>     the above license grant, this restriction and the following disclaimer,
>     must be included in all copies of the Software, in whole or in part, and
>     all derivative works of the Software, unless such copies or derivative
>     works are solely in the form of machine-executable object code generated by
>     a source language processor.
>     
>     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
>     IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
>     FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT
>     SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE
>     FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE,
>     ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
>     DEALINGS IN THE SOFTWARE.

## libmspack

https://www.cabextract.org.uk/libmspack/

See below for license text (LGPLv2).

## pugixml

https://github.com/zeux/pugixml

Copyright (c) 2006-2024 Arseny Kapoulkine

See below for license text (MIT).

## fmtlib

https://github.com/fmtlib/fmt

Copyright (c) 2012 - present, Victor Zverovich and {fmt} contributors

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

--- Optional exception to the license ---

As an exception, if, as a result of your compiling your source code, portions
of this Software are embedded into a machine-executable object form of such
source code, you may redistribute such embedded portions in such object form
without including the above copyright and permission notices.

## concurrent_queue & readerwriterqueue

https://github.com/cameron314/concurrentqueue
https://github.com/cameron314/readerwriterqueue

Simplified BSD License:

Copyright (c) 2013-2016, Cameron Desrochers. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

    Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## libass
https://github.com/libass/libass

See below for license text (ISC)

## boost

https://boost.org

See below for license text (Boost Software License, Version 1.0)

## toml11

https://github.com/ToruNiina/toml11

See below for license text (MIT).

# Generic license texts

## 2-clause BSD license

>     Redistribution and use in source and binary forms, with or without modification,
>     are permitted provided that the following conditions are met:
>
>     1. Redistributions of source code must retain the above copyright notice, this
>     list of conditions and the following disclaimer.
>
>     2. Redistributions in binary form must reproduce the above copyright notice,
>     this list of conditions and the following disclaimer in the documentation and/or
>     other materials provided with the distribution.
>
>     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
>     ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
>     WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
>     DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
>     ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
>     (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
>     LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
>     ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
>     (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
>     SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## 3-clause BSD license

>     Redistribution and use in source and binary forms, with or without
>     modification, are permitted provided that the following conditions are met:
>
>     1. Redistributions of source code must retain the above copyright notice, this
>        list of conditions and the following disclaimer.
>
>     2. Redistributions in binary form must reproduce the above copyright notice,
>        this list of conditions and the following disclaimer in the documentation
>        and/or other materials provided with the distribution.
>
>     3. Neither the name of the copyright holder nor the names of its
>        contributors may be used to endorse or promote products derived from
>        this software without specific prior written permission.
>
>     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
>     AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
>     IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
>     DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
>     FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
>     DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
>     SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
>     CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
>     OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
>     OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Apache License, Version 2.0

>                                     Apache License
>                               Version 2.0, January 2004
>                            http://www.apache.org/licenses/
>
>       TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION
>
>       1. Definitions.
>
>          "License" shall mean the terms and conditions for use, reproduction,
>          and distribution as defined by Sections 1 through 9 of this document.
>
>          "Licensor" shall mean the copyright owner or entity authorized by
>          the copyright owner that is granting the License.
>
>          "Legal Entity" shall mean the union of the acting entity and all
>          other entities that control, are controlled by, or are under common
>          control with that entity. For the purposes of this definition,
>          "control" means (i) the power, direct or indirect, to cause the
>          direction or management of such entity, whether by contract or
>          otherwise, or (ii) ownership of fifty percent (50%) or more of the
>          outstanding shares, or (iii) beneficial ownership of such entity.
>
>          "You" (or "Your") shall mean an individual or Legal Entity
>          exercising permissions granted by this License.
>
>          "Source" form shall mean the preferred form for making modifications,
>          including but not limited to software source code, documentation
>          source, and configuration files.
>
>          "Object" form shall mean any form resulting from mechanical
>          transformation or translation of a Source form, including but
>          not limited to compiled object code, generated documentation,
>          and conversions to other media types.
>
>          "Work" shall mean the work of authorship, whether in Source or
>          Object form, made available under the License, as indicated by a
>          copyright notice that is included in or attached to the work
>          (an example is provided in the Appendix below).
>
>          "Derivative Works" shall mean any work, whether in Source or Object
>          form, that is based on (or derived from) the Work and for which the
>          editorial revisions, annotations, elaborations, or other modifications
>          represent, as a whole, an original work of authorship. For the purposes
>          of this License, Derivative Works shall not include works that remain
>          separable from, or merely link (or bind by name) to the interfaces of,
>          the Work and Derivative Works thereof.
>
>          "Contribution" shall mean any work of authorship, including
>          the original version of the Work and any modifications or additions
>          to that Work or Derivative Works thereof, that is intentionally
>          submitted to Licensor for inclusion in the Work by the copyright owner
>          or by an individual or Legal Entity authorized to submit on behalf of
>          the copyright owner. For the purposes of this definition, "submitted"
>          means any form of electronic, verbal, or written communication sent
>          to the Licensor or its representatives, including but not limited to
>          communication on electronic mailing lists, source code control systems,
>          and issue tracking systems that are managed by, or on behalf of, the
>          Licensor for the purpose of discussing and improving the Work, but
>          excluding communication that is conspicuously marked or otherwise
>          designated in writing by the copyright owner as "Not a Contribution."
>
>          "Contributor" shall mean Licensor and any individual or Legal Entity
>          on behalf of whom a Contribution has been received by Licensor and
>          subsequently incorporated within the Work.
>
>       2. Grant of Copyright License. Subject to the terms and conditions of
>          this License, each Contributor hereby grants to You a perpetual,
>          worldwide, non-exclusive, no-charge, royalty-free, irrevocable
>          copyright license to reproduce, prepare Derivative Works of,
>          publicly display, publicly perform, sublicense, and distribute the
>          Work and such Derivative Works in Source or Object form.
>
>       3. Grant of Patent License. Subject to the terms and conditions of
>          this License, each Contributor hereby grants to You a perpetual,
>          worldwide, non-exclusive, no-charge, royalty-free, irrevocable
>          (except as stated in this section) patent license to make, have made,
>          use, offer to sell, sell, import, and otherwise transfer the Work,
>          where such license applies only to those patent claims licensable
>          by such Contributor that are necessarily infringed by their
>          Contribution(s) alone or by combination of their Contribution(s)
>          with the Work to which such Contribution(s) was submitted. If You
>          institute patent litigation against any entity (including a
>          cross-claim or counterclaim in a lawsuit) alleging that the Work
>          or a Contribution incorporated within the Work constitutes direct
>          or contributory patent infringement, then any patent licenses
>          granted to You under this License for that Work shall terminate
>          as of the date such litigation is filed.
>
>       4. Redistribution. You may reproduce and distribute copies of the
>          Work or Derivative Works thereof in any medium, with or without
>          modifications, and in Source or Object form, provided that You
>          meet the following conditions:
>
>          (a) You must give any other recipients of the Work or
>              Derivative Works a copy of this License; and
>
>          (b) You must cause any modified files to carry prominent notices
>              stating that You changed the files; and
>
>          (c) You must retain, in the Source form of any Derivative Works
>              that You distribute, all copyright, patent, trademark, and
>              attribution notices from the Source form of the Work,
>              excluding those notices that do not pertain to any part of
>              the Derivative Works; and
>
>          (d) If the Work includes a "NOTICE" text file as part of its
>              distribution, then any Derivative Works that You distribute must
>              include a readable copy of the attribution notices contained
>              within such NOTICE file, excluding those notices that do not
>              pertain to any part of the Derivative Works, in at least one
>              of the following places: within a NOTICE text file distributed
>              as part of the Derivative Works; within the Source form or
>              documentation, if provided along with the Derivative Works; or,
>              within a display generated by the Derivative Works, if and
>              wherever such third-party notices normally appear. The contents
>              of the NOTICE file are for informational purposes only and
>              do not modify the License. You may add Your own attribution
>              notices within Derivative Works that You distribute, alongside
>              or as an addendum to the NOTICE text from the Work, provided
>              that such additional attribution notices cannot be construed
>              as modifying the License.
>
>          You may add Your own copyright statement to Your modifications and
>          may provide additional or different license terms and conditions
>          for use, reproduction, or distribution of Your modifications, or
>          for any such Derivative Works as a whole, provided Your use,
>          reproduction, and distribution of the Work otherwise complies with
>          the conditions stated in this License.
>
>       5. Submission of Contributions. Unless You explicitly state otherwise,
>          any Contribution intentionally submitted for inclusion in the Work
>          by You to the Licensor shall be under the terms and conditions of
>          this License, without any additional terms or conditions.
>          Notwithstanding the above, nothing herein shall supersede or modify
>          the terms of any separate license agreement you may have executed
>          with Licensor regarding such Contributions.
>
>       6. Trademarks. This License does not grant permission to use the trade
>          names, trademarks, service marks, or product names of the Licensor,
>          except as required for reasonable and customary use in describing the
>          origin of the Work and reproducing the content of the NOTICE file.
>
>       7. Disclaimer of Warranty. Unless required by applicable law or
>          agreed to in writing, Licensor provides the Work (and each
>          Contributor provides its Contributions) on an "AS IS" BASIS,
>          WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
>          implied, including, without limitation, any warranties or conditions
>          of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
>          PARTICULAR PURPOSE. You are solely responsible for determining the
>          appropriateness of using or redistributing the Work and assume any
>          risks associated with Your exercise of permissions under this License.
>
>       8. Limitation of Liability. In no event and under no legal theory,
>          whether in tort (including negligence), contract, or otherwise,
>          unless required by applicable law (such as deliberate and grossly
>          negligent acts) or agreed to in writing, shall any Contributor be
>          liable to You for damages, including any direct, indirect, special,
>          incidental, or consequential damages of any character arising as a
>          result of this License or out of the use or inability to use the
>          Work (including but not limited to damages for loss of goodwill,
>          work stoppage, computer failure or malfunction, or any and all
>          other commercial damages or losses), even if such Contributor
>          has been advised of the possibility of such damages.
>
>       9. Accepting Warranty or Additional Liability. While redistributing
>          the Work or Derivative Works thereof, You may choose to offer,
>          and charge a fee for, acceptance of support, warranty, indemnity,
>          or other liability obligations and/or rights consistent with this
>          License. However, in accepting such obligations, You may act only
>          on Your own behalf and on Your sole responsibility, not on behalf
>          of any other Contributor, and only if You agree to indemnify,
>          defend, and hold each Contributor harmless for any liability
>          incurred by, or claims asserted against, such Contributor by reason
>          of your accepting any such warranty or additional liability.
>
>       END OF TERMS AND CONDITIONS
>
>       APPENDIX: How to apply the Apache License to your work.
>
>          To apply the Apache License to your work, attach the following
>          boilerplate notice, with the fields enclosed by brackets "{}"
>          replaced with your own identifying information. (Don't include
>          the brackets!)  The text should be enclosed in the appropriate
>          comment syntax for the file format. We also recommend that a
>          file or class name and description of purpose be included on the
>          same "printed page" as the copyright notice for easier
>          identification within third-party archives.
>
>       Copyright {yyyy} {name of copyright owner}
>
>       Licensed under the Apache License, Version 2.0 (the "License");
>       you may not use this file except in compliance with the License.
>       You may obtain a copy of the License at
>
>           http://www.apache.org/licenses/LICENSE-2.0
>
>       Unless required by applicable law or agreed to in writing, software
>       distributed under the License is distributed on an "AS IS" BASIS,
>       WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
>       See the License for the specific language governing permissions and
>       limitations under the License.

## Boost Software License, Version 1.0

>     Boost Software License - Version 1.0 - August 17th, 2003
>
>     Permission is hereby granted, free of charge, to any person or organization
>     obtaining a copy of the software and accompanying documentation covered by
>     this license (the "Software") to use, reproduce, display, distribute,
>     execute, and transmit the Software, and to prepare derivative works of the
>     Software, and to permit third-parties to whom the Software is furnished to
>     do so, all subject to the following:
>
>     The copyright notices in the Software and this entire statement, including
>     the above license grant, this restriction and the following disclaimer,
>     must be included in all copies of the Software, in whole or in part, and
>     all derivative works of the Software, unless such copies or derivative
>     works are solely in the form of machine-executable object code generated by
>     a source language processor.
>
>     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
>     IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
>     FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT
>     SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE
>     FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE,
>     ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
>     DEALINGS IN THE SOFTWARE.

## CC0 1.0

>     Creative Commons Legal Code
>
>     CC0 1.0 Universal
>
>         CREATIVE COMMONS CORPORATION IS NOT A LAW FIRM AND DOES NOT PROVIDE
>         LEGAL SERVICES. DISTRIBUTION OF THIS DOCUMENT DOES NOT CREATE AN
>         ATTORNEY-CLIENT RELATIONSHIP. CREATIVE COMMONS PROVIDES THIS
>         INFORMATION ON AN "AS-IS" BASIS. CREATIVE COMMONS MAKES NO WARRANTIES
>         REGARDING THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS
>         PROVIDED HEREUNDER, AND DISCLAIMS LIABILITY FOR DAMAGES RESULTING FROM
>         THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS PROVIDED
>         HEREUNDER.
>
>     Statement of Purpose
>
>     The laws of most jurisdictions throughout the world automatically confer
>     exclusive Copyright and Related Rights (defined below) upon the creator
>     and subsequent owner(s) (each and all, an "owner") of an original work of
>     authorship and/or a database (each, a "Work").
>
>     Certain owners wish to permanently relinquish those rights to a Work for
>     the purpose of contributing to a commons of creative, cultural and
>     scientific works ("Commons") that the public can reliably and without fear
>     of later claims of infringement build upon, modify, incorporate in other
>     works, reuse and redistribute as freely as possible in any form whatsoever
>     and for any purposes, including without limitation commercial purposes.
>     These owners may contribute to the Commons to promote the ideal of a free
>     culture and the further production of creative, cultural and scientific
>     works, or to gain reputation or greater distribution for their Work in
>     part through the use and efforts of others.
>
>     For these and/or other purposes and motivations, and without any
>     expectation of additional consideration or compensation, the person
>     associating CC0 with a Work (the "Affirmer"), to the extent that he or she
>     is an owner of Copyright and Related Rights in the Work, voluntarily
>     elects to apply CC0 to the Work and publicly distribute the Work under its
>     terms, with knowledge of his or her Copyright and Related Rights in the
>     Work and the meaning and intended legal effect of CC0 on those rights.
>
>     1. Copyright and Related Rights. A Work made available under CC0 may be
>     protected by copyright and related or neighboring rights ("Copyright and
>     Related Rights"). Copyright and Related Rights include, but are not
>     limited to, the following:
>
>       i. the right to reproduce, adapt, distribute, perform, display,
>          communicate, and translate a Work;
>      ii. moral rights retained by the original author(s) and/or performer(s);
>     iii. publicity and privacy rights pertaining to a person's image or
>          likeness depicted in a Work;
>      iv. rights protecting against unfair competition in regards to a Work,
>          subject to the limitations in paragraph 4(a), below;
>       v. rights protecting the extraction, dissemination, use and reuse of data
>          in a Work;
>      vi. database rights (such as those arising under Directive 96/9/EC of the
>          European Parliament and of the Council of 11 March 1996 on the legal
>          protection of databases, and under any national implementation
>          thereof, including any amended or successor version of such
>          directive); and
>     vii. other similar, equivalent or corresponding rights throughout the
>          world based on applicable law or treaty, and any national
>          implementations thereof.
>
>     2. Waiver. To the greatest extent permitted by, but not in contravention
>     of, applicable law, Affirmer hereby overtly, fully, permanently,
>     irrevocably and unconditionally waives, abandons, and surrenders all of
>     Affirmer's Copyright and Related Rights and associated claims and causes
>     of action, whether now known or unknown (including existing as well as
>     future claims and causes of action), in the Work (i) in all territories
>     worldwide, (ii) for the maximum duration provided by applicable law or
>     treaty (including future time extensions), (iii) in any current or future
>     medium and for any number of copies, and (iv) for any purpose whatsoever,
>     including without limitation commercial, advertising or promotional
>     purposes (the "Waiver"). Affirmer makes the Waiver for the benefit of each
>     member of the public at large and to the detriment of Affirmer's heirs and
>     successors, fully intending that such Waiver shall not be subject to
>     revocation, rescission, cancellation, termination, or any other legal or
>     equitable action to disrupt the quiet enjoyment of the Work by the public
>     as contemplated by Affirmer's express Statement of Purpose.
>
>     3. Public License Fallback. Should any part of the Waiver for any reason
>     be judged legally invalid or ineffective under applicable law, then the
>     Waiver shall be preserved to the maximum extent permitted taking into
>     account Affirmer's express Statement of Purpose. In addition, to the
>     extent the Waiver is so judged Affirmer hereby grants to each affected
>     person a royalty-free, non transferable, non sublicensable, non exclusive,
>     irrevocable and unconditional license to exercise Affirmer's Copyright and
>     Related Rights in the Work (i) in all territories worldwide, (ii) for the
>     maximum duration provided by applicable law or treaty (including future
>     time extensions), (iii) in any current or future medium and for any number
>     of copies, and (iv) for any purpose whatsoever, including without
>     limitation commercial, advertising or promotional purposes (the
>     "License"). The License shall be deemed effective as of the date CC0 was
>     applied by Affirmer to the Work. Should any part of the License for any
>     reason be judged legally invalid or ineffective under applicable law, such
>     partial invalidity or ineffectiveness shall not invalidate the remainder
>     of the License, and in such case Affirmer hereby affirms that he or she
>     will not (i) exercise any of his or her remaining Copyright and Related
>     Rights in the Work or (ii) assert any associated claims and causes of
>     action with respect to the Work, in either case contrary to Affirmer's
>     express Statement of Purpose.
>
>     4. Limitations and Disclaimers.
>
>      a. No trademark or patent rights held by Affirmer are waived, abandoned,
>         surrendered, licensed or otherwise affected by this document.
>      b. Affirmer offers the Work as-is and makes no representations or
>         warranties of any kind concerning the Work, express, implied,
>         statutory or otherwise, including without limitation warranties of
>         title, merchantability, fitness for a particular purpose, non
>         infringement, or the absence of latent or other defects, accuracy, or
>         the present or absence of errors, whether or not discoverable, all to
>         the greatest extent permissible under applicable law.
>      c. Affirmer disclaims responsibility for clearing rights of other persons
>         that may apply to the Work or any use thereof, including without
>         limitation any person's Copyright and Related Rights in the Work.
>         Further, Affirmer disclaims responsibility for obtaining any necessary
>         consents, permissions or other rights required for any use of the
>         Work.
>      d. Affirmer understands and acknowledges that Creative Commons is not a
>         party to this document and has no duty or obligation with respect to
>         this CC0 or use of the Work.

## ISC license

>     Permission to use, copy, modify, and distribute this software for any
>     purpose with or without fee is hereby granted, provided that the above
>     copyright notice and this permission notice appear in all copies.
>
>     THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
>     WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
>     MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
>     ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
>     WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
>     ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
>     OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

## LGPLv2 (GNU Library General Public License, Version 2)

>                       GNU LIBRARY GENERAL PUBLIC LICENSE
>                            Version 2, June 1991
>
>      Copyright (C) 1991 Free Software Foundation, Inc.
>      51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
>      Everyone is permitted to copy and distribute verbatim copies
>      of this license document, but changing it is not allowed.
>
>     [This is the first released version of the library GPL.  It is
>      numbered 2 because it goes with version 2 of the ordinary GPL.]
>
>                                 Preamble
>
>       The licenses for most software are designed to take away your
>     freedom to share and change it.  By contrast, the GNU General Public
>     Licenses are intended to guarantee your freedom to share and change
>     free software--to make sure the software is free for all its users.
>
>       This license, the Library General Public License, applies to some
>     specially designated Free Software Foundation software, and to any
>     other libraries whose authors decide to use it.  You can use it for
>     your libraries, too.
>
>       When we speak of free software, we are referring to freedom, not
>     price.  Our General Public Licenses are designed to make sure that you
>     have the freedom to distribute copies of free software (and charge for
>     this service if you wish), that you receive source code or can get it
>     if you want it, that you can change the software or use pieces of it
>     in new free programs; and that you know you can do these things.
>
>       To protect your rights, we need to make restrictions that forbid
>     anyone to deny you these rights or to ask you to surrender the rights.
>     These restrictions translate to certain responsibilities for you if
>     you distribute copies of the library, or if you modify it.
>
>       For example, if you distribute copies of the library, whether gratis
>     or for a fee, you must give the recipients all the rights that we gave
>     you.  You must make sure that they, too, receive or can get the source
>     code.  If you link a program with the library, you must provide
>     complete object files to the recipients so that they can relink them
>     with the library, after making changes to the library and recompiling
>     it.  And you must show them these terms so they know their rights.
>
>       Our method of protecting your rights has two steps: (1) copyright
>     the library, and (2) offer you this license which gives you legal
>     permission to copy, distribute and/or modify the library.
>
>       Also, for each distributor's protection, we want to make certain
>     that everyone understands that there is no warranty for this free
>     library.  If the library is modified by someone else and passed on, we
>     want its recipients to know that what they have is not the original
>     version, so that any problems introduced by others will not reflect on
>     the original authors' reputations.
>
>       Finally, any free program is threatened constantly by software
>     patents.  We wish to avoid the danger that companies distributing free
>     software will individually obtain patent licenses, thus in effect
>     transforming the program into proprietary software.  To prevent this,
>     we have made it clear that any patent must be licensed for everyone's
>     free use or not licensed at all.
>
>       Most GNU software, including some libraries, is covered by the ordinary
>     GNU General Public License, which was designed for utility programs.  This
>     license, the GNU Library General Public License, applies to certain
>     designated libraries.  This license is quite different from the ordinary
>     one; be sure to read it in full, and don't assume that anything in it is
>     the same as in the ordinary license.
>
>       The reason we have a separate public license for some libraries is that
>     they blur the distinction we usually make between modifying or adding to a
>     program and simply using it.  Linking a program with a library, without
>     changing the library, is in some sense simply using the library, and is
>     analogous to running a utility program or application program.  However, in
>     a textual and legal sense, the linked executable is a combined work, a
>     derivative of the original library, and the ordinary General Public License
>     treats it as such.
>
>       Because of this blurred distinction, using the ordinary General
>     Public License for libraries did not effectively promote software
>     sharing, because most developers did not use the libraries.  We
>     concluded that weaker conditions might promote sharing better.
>
>       However, unrestricted linking of non-free programs would deprive the
>     users of those programs of all benefit from the free status of the
>     libraries themselves.  This Library General Public License is intended to
>     permit developers of non-free programs to use free libraries, while
>     preserving your freedom as a user of such programs to change the free
>     libraries that are incorporated in them.  (We have not seen how to achieve
>     this as regards changes in header files, but we have achieved it as regards
>     changes in the actual functions of the Library.)  The hope is that this
>     will lead to faster development of free libraries.
>
>       The precise terms and conditions for copying, distribution and
>     modification follow.  Pay close attention to the difference between a
>     "work based on the library" and a "work that uses the library".  The
>     former contains code derived from the library, while the latter only
>     works together with the library.
>
>       Note that it is possible for a library to be covered by the ordinary
>     General Public License rather than by this special one.
>
>                       GNU LIBRARY GENERAL PUBLIC LICENSE
>        TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
>
>       0. This License Agreement applies to any software library which
>     contains a notice placed by the copyright holder or other authorized
>     party saying it may be distributed under the terms of this Library
>     General Public License (also called "this License").  Each licensee is
>     addressed as "you".
>
>       A "library" means a collection of software functions and/or data
>     prepared so as to be conveniently linked with application programs
>     (which use some of those functions and data) to form executables.
>
>       The "Library", below, refers to any such software library or work
>     which has been distributed under these terms.  A "work based on the
>     Library" means either the Library or any derivative work under
>     copyright law: that is to say, a work containing the Library or a
>     portion of it, either verbatim or with modifications and/or translated
>     straightforwardly into another language.  (Hereinafter, translation is
>     included without limitation in the term "modification".)
>
>       "Source code" for a work means the preferred form of the work for
>     making modifications to it.  For a library, complete source code means
>     all the source code for all modules it contains, plus any associated
>     interface definition files, plus the scripts used to control compilation
>     and installation of the library.
>
>       Activities other than copying, distribution and modification are not
>     covered by this License; they are outside its scope.  The act of
>     running a program using the Library is not restricted, and output from
>     such a program is covered only if its contents constitute a work based
>     on the Library (independent of the use of the Library in a tool for
>     writing it).  Whether that is true depends on what the Library does
>     and what the program that uses the Library does.
>
>       1. You may copy and distribute verbatim copies of the Library's
>     complete source code as you receive it, in any medium, provided that
>     you conspicuously and appropriately publish on each copy an
>     appropriate copyright notice and disclaimer of warranty; keep intact
>     all the notices that refer to this License and to the absence of any
>     warranty; and distribute a copy of this License along with the
>     Library.
>
>       You may charge a fee for the physical act of transferring a copy,
>     and you may at your option offer warranty protection in exchange for a
>     fee.
>
>       2. You may modify your copy or copies of the Library or any portion
>     of it, thus forming a work based on the Library, and copy and
>     distribute such modifications or work under the terms of Section 1
>     above, provided that you also meet all of these conditions:
>
>         a) The modified work must itself be a software library.
>
>         b) You must cause the files modified to carry prominent notices
>         stating that you changed the files and the date of any change.
>
>         c) You must cause the whole of the work to be licensed at no
>         charge to all third parties under the terms of this License.
>
>         d) If a facility in the modified Library refers to a function or a
>         table of data to be supplied by an application program that uses
>         the facility, other than as an argument passed when the facility
>         is invoked, then you must make a good faith effort to ensure that,
>         in the event an application does not supply such function or
>         table, the facility still operates, and performs whatever part of
>         its purpose remains meaningful.
>
>         (For example, a function in a library to compute square roots has
>         a purpose that is entirely well-defined independent of the
>         application.  Therefore, Subsection 2d requires that any
>         application-supplied function or table used by this function must
>         be optional: if the application does not supply it, the square
>         root function must still compute square roots.)
>
>     These requirements apply to the modified work as a whole.  If
>     identifiable sections of that work are not derived from the Library,
>     and can be reasonably considered independent and separate works in
>     themselves, then this License, and its terms, do not apply to those
>     sections when you distribute them as separate works.  But when you
>     distribute the same sections as part of a whole which is a work based
>     on the Library, the distribution of the whole must be on the terms of
>     this License, whose permissions for other licensees extend to the
>     entire whole, and thus to each and every part regardless of who wrote
>     it.
>
>     Thus, it is not the intent of this section to claim rights or contest
>     your rights to work written entirely by you; rather, the intent is to
>     exercise the right to control the distribution of derivative or
>     collective works based on the Library.
>
>     In addition, mere aggregation of another work not based on the Library
>     with the Library (or with a work based on the Library) on a volume of
>     a storage or distribution medium does not bring the other work under
>     the scope of this License.
>
>       3. You may opt to apply the terms of the ordinary GNU General Public
>     License instead of this License to a given copy of the Library.  To do
>     this, you must alter all the notices that refer to this License, so
>     that they refer to the ordinary GNU General Public License, version 2,
>     instead of to this License.  (If a newer version than version 2 of the
>     ordinary GNU General Public License has appeared, then you can specify
>     that version instead if you wish.)  Do not make any other change in
>     these notices.
>
>       Once this change is made in a given copy, it is irreversible for
>     that copy, so the ordinary GNU General Public License applies to all
>     subsequent copies and derivative works made from that copy.
>
>       This option is useful when you wish to copy part of the code of
>     the Library into a program that is not a library.
>
>       4. You may copy and distribute the Library (or a portion or
>     derivative of it, under Section 2) in object code or executable form
>     under the terms of Sections 1 and 2 above provided that you accompany
>     it with the complete corresponding machine-readable source code, which
>     must be distributed under the terms of Sections 1 and 2 above on a
>     medium customarily used for software interchange.
>
>       If distribution of object code is made by offering access to copy
>     from a designated place, then offering equivalent access to copy the
>     source code from the same place satisfies the requirement to
>     distribute the source code, even though third parties are not
>     compelled to copy the source along with the object code.
>
>       5. A program that contains no derivative of any portion of the
>     Library, but is designed to work with the Library by being compiled or
>     linked with it, is called a "work that uses the Library".  Such a
>     work, in isolation, is not a derivative work of the Library, and
>     therefore falls outside the scope of this License.
>
>       However, linking a "work that uses the Library" with the Library
>     creates an executable that is a derivative of the Library (because it
>     contains portions of the Library), rather than a "work that uses the
>     library".  The executable is therefore covered by this License.
>     Section 6 states terms for distribution of such executables.
>
>       When a "work that uses the Library" uses material from a header file
>     that is part of the Library, the object code for the work may be a
>     derivative work of the Library even though the source code is not.
>     Whether this is true is especially significant if the work can be
>     linked without the Library, or if the work is itself a library.  The
>     threshold for this to be true is not precisely defined by law.
>
>       If such an object file uses only numerical parameters, data
>     structure layouts and accessors, and small macros and small inline
>     functions (ten lines or less in length), then the use of the object
>     file is unrestricted, regardless of whether it is legally a derivative
>     work.  (Executables containing this object code plus portions of the
>     Library will still fall under Section 6.)
>
>       Otherwise, if the work is a derivative of the Library, you may
>     distribute the object code for the work under the terms of Section 6.
>     Any executables containing that work also fall under Section 6,
>     whether or not they are linked directly with the Library itself.
>
>       6. As an exception to the Sections above, you may also compile or
>     link a "work that uses the Library" with the Library to produce a
>     work containing portions of the Library, and distribute that work
>     under terms of your choice, provided that the terms permit
>     modification of the work for the customer's own use and reverse
>     engineering for debugging such modifications.
>
>       You must give prominent notice with each copy of the work that the
>     Library is used in it and that the Library and its use are covered by
>     this License.  You must supply a copy of this License.  If the work
>     during execution displays copyright notices, you must include the
>     copyright notice for the Library among them, as well as a reference
>     directing the user to the copy of this License.  Also, you must do one
>     of these things:
>
>         a) Accompany the work with the complete corresponding
>         machine-readable source code for the Library including whatever
>         changes were used in the work (which must be distributed under
>         Sections 1 and 2 above); and, if the work is an executable linked
>         with the Library, with the complete machine-readable "work that
>         uses the Library", as object code and/or source code, so that the
>         user can modify the Library and then relink to produce a modified
>         executable containing the modified Library.  (It is understood
>         that the user who changes the contents of definitions files in the
>         Library will not necessarily be able to recompile the application
>         to use the modified definitions.)
>
>         b) Accompany the work with a written offer, valid for at
>         least three years, to give the same user the materials
>         specified in Subsection 6a, above, for a charge no more
>         than the cost of performing this distribution.
>
>         c) If distribution of the work is made by offering access to copy
>         from a designated place, offer equivalent access to copy the above
>         specified materials from the same place.
>
>         d) Verify that the user has already received a copy of these
>         materials or that you have already sent this user a copy.
>
>       For an executable, the required form of the "work that uses the
>     Library" must include any data and utility programs needed for
>     reproducing the executable from it.  However, as a special exception,
>     the source code distributed need not include anything that is normally
>     distributed (in either source or binary form) with the major
>     components (compiler, kernel, and so on) of the operating system on
>     which the executable runs, unless that component itself accompanies
>     the executable.
>
>       It may happen that this requirement contradicts the license
>     restrictions of other proprietary libraries that do not normally
>     accompany the operating system.  Such a contradiction means you cannot
>     use both them and the Library together in an executable that you
>     distribute.
>
>       7. You may place library facilities that are a work based on the
>     Library side-by-side in a single library together with other library
>     facilities not covered by this License, and distribute such a combined
>     library, provided that the separate distribution of the work based on
>     the Library and of the other library facilities is otherwise
>     permitted, and provided that you do these two things:
>
>         a) Accompany the combined library with a copy of the same work
>         based on the Library, uncombined with any other library
>         facilities.  This must be distributed under the terms of the
>         Sections above.
>
>         b) Give prominent notice with the combined library of the fact
>         that part of it is a work based on the Library, and explaining
>         where to find the accompanying uncombined form of the same work.
>
>       8. You may not copy, modify, sublicense, link with, or distribute
>     the Library except as expressly provided under this License.  Any
>     attempt otherwise to copy, modify, sublicense, link with, or
>     distribute the Library is void, and will automatically terminate your
>     rights under this License.  However, parties who have received copies,
>     or rights, from you under this License will not have their licenses
>     terminated so long as such parties remain in full compliance.
>
>       9. You are not required to accept this License, since you have not
>     signed it.  However, nothing else grants you permission to modify or
>     distribute the Library or its derivative works.  These actions are
>     prohibited by law if you do not accept this License.  Therefore, by
>     modifying or distributing the Library (or any work based on the
>     Library), you indicate your acceptance of this License to do so, and
>     all its terms and conditions for copying, distributing or modifying
>     the Library or works based on it.
>
>       10. Each time you redistribute the Library (or any work based on the
>     Library), the recipient automatically receives a license from the
>     original licensor to copy, distribute, link with or modify the Library
>     subject to these terms and conditions.  You may not impose any further
>     restrictions on the recipients' exercise of the rights granted herein.
>     You are not responsible for enforcing compliance by third parties to
>     this License.
>
>       11. If, as a consequence of a court judgment or allegation of patent
>     infringement or for any other reason (not limited to patent issues),
>     conditions are imposed on you (whether by court order, agreement or
>     otherwise) that contradict the conditions of this License, they do not
>     excuse you from the conditions of this License.  If you cannot
>     distribute so as to satisfy simultaneously your obligations under this
>     License and any other pertinent obligations, then as a consequence you
>     may not distribute the Library at all.  For example, if a patent
>     license would not permit royalty-free redistribution of the Library by
>     all those who receive copies directly or indirectly through you, then
>     the only way you could satisfy both it and this License would be to
>     refrain entirely from distribution of the Library.
>
>     If any portion of this section is held invalid or unenforceable under any
>     particular circumstance, the balance of the section is intended to apply,
>     and the section as a whole is intended to apply in other circumstances.
>
>     It is not the purpose of this section to induce you to infringe any
>     patents or other property right claims or to contest validity of any
>     such claims; this section has the sole purpose of protecting the
>     integrity of the free software distribution system which is
>     implemented by public license practices.  Many people have made
>     generous contributions to the wide range of software distributed
>     through that system in reliance on consistent application of that
>     system; it is up to the author/donor to decide if he or she is willing
>     to distribute software through any other system and a licensee cannot
>     impose that choice.
>
>     This section is intended to make thoroughly clear what is believed to
>     be a consequence of the rest of this License.
>
>       12. If the distribution and/or use of the Library is restricted in
>     certain countries either by patents or by copyrighted interfaces, the
>     original copyright holder who places the Library under this License may add
>     an explicit geographical distribution limitation excluding those countries,
>     so that distribution is permitted only in or among countries not thus
>     excluded.  In such case, this License incorporates the limitation as if
>     written in the body of this License.
>
>       13. The Free Software Foundation may publish revised and/or new
>     versions of the Library General Public License from time to time.
>     Such new versions will be similar in spirit to the present version,
>     but may differ in detail to address new problems or concerns.
>
>     Each version is given a distinguishing version number.  If the Library
>     specifies a version number of this License which applies to it and
>     "any later version", you have the option of following the terms and
>     conditions either of that version or of any later version published by
>     the Free Software Foundation.  If the Library does not specify a
>     license version number, you may choose any version ever published by
>     the Free Software Foundation.
>
>       14. If you wish to incorporate parts of the Library into other free
>     programs whose distribution conditions are incompatible with these,
>     write to the author to ask for permission.  For software which is
>     copyrighted by the Free Software Foundation, write to the Free
>     Software Foundation; we sometimes make exceptions for this.  Our
>     decision will be guided by the two goals of preserving the free status
>     of all derivatives of our free software and of promoting the sharing
>     and reuse of software generally.
>
>                                 NO WARRANTY
>
>       15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
>     WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
>     EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
>     OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
>     KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
>     IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
>     PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
>     LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
>     THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.
>
>       16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
>     WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
>     AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
>     FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
>     CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
>     LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
>     RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
>     FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
>     SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
>     DAMAGES.
>
>                          END OF TERMS AND CONDITIONS
>
>                How to Apply These Terms to Your New Libraries
>
>       If you develop a new library, and you want it to be of the greatest
>     possible use to the public, we recommend making it free software that
>     everyone can redistribute and change.  You can do so by permitting
>     redistribution under these terms (or, alternatively, under the terms of the
>     ordinary General Public License).
>
>       To apply these terms, attach the following notices to the library.  It is
>     safest to attach them to the start of each source file to most effectively
>     convey the exclusion of warranty; and each file should have at least the
>     "copyright" line and a pointer to where the full notice is found.
>
>         <one line to give the library's name and a brief idea of what it does.>
>         Copyright (C) <year>  <name of author>
>
>         This library is free software; you can redistribute it and/or
>         modify it under the terms of the GNU Library General Public
>         License as published by the Free Software Foundation; either
>         version 2 of the License, or (at your option) any later version.
>
>         This library is distributed in the hope that it will be useful,
>         but WITHOUT ANY WARRANTY; without even the implied warranty of
>         MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
>         Library General Public License for more details.
>
>         You should have received a copy of the GNU Library General Public
>         License along with this library; if not, write to the Free Software
>         Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
>
>     Also add information on how to contact you by electronic and paper mail.
>
>     You should also get your employer (if you work as a programmer) or your
>     school, if any, to sign a "copyright disclaimer" for the library, if
>     necessary.  Here is a sample; alter the names:
>
>       Yoyodyne, Inc., hereby disclaims all copyright interest in the
>       library `Frob' (a library for tweaking knobs) written by James Random Hacker.
>
>       <signature of Ty Coon>, 1 April 1990
>       Ty Coon, President of Vice
>
>     That's all there is to it!

## MIT License

>     Permission is hereby granted, free of charge, to any person obtaining a copy
>     of this software and associated documentation files (the "Software"), to deal
>     in the Software without restriction, including without limitation the rights
>     to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
>     copies of the Software, and to permit persons to whom the Software is
>     furnished to do so, subject to the following conditions:
>
>     The above copyright notice and this permission notice shall be included in
>     all copies or substantial portions of the Software.
>
>     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
>     IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
>     FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
>     AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
>     LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
>     OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
>     THE SOFTWARE.

## zlib license

>     This software is provided 'as-is', without any express or implied
>     warranty.  In no event will the authors be held liable for any damages
>     arising from the use of this software.
>
>     Permission is granted to anyone to use this software for any purpose,
>     including commercial applications, and to alter it and redistribute it
>     freely, subject to the following restrictions:
>
>     1. The origin of this software must not be misrepresented; you must not
>        claim that you wrote the original software. If you use this software
>        in a product, an acknowledgment in the product documentation would be
>        appreciated but is not required.
>     2. Altered source versions must be plainly marked as such, and must not be
>        misrepresented as being the original software.
>     3. This notice may not be removed or altered from any source distribution.

