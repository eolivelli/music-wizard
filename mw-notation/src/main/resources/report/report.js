/*
 * Copyright 2026 Music Wizard contributors
 * Licensed under the Apache License, Version 2.0.
 *
 * The whole script of the analysis report. It only adds two things a phone
 * cannot get otherwise -- the overview as a way to move the strip, and a tap
 * as a substitute for a hover -- so a browser that runs none of it still gets
 * the complete page.
 */

(function () {
  'use strict';

  var scroller = document.getElementById('mw-scroll');
  var readout = document.getElementById('mw-readout');
  var overview = document.querySelector('#overview svg');

  function stripWidth() {
    var strip = scroller ? scroller.querySelector('svg') : null;
    return strip ? strip.getBoundingClientRect().width : 0;
  }

  function say(text) {
    if (readout) {
      readout.textContent = text;
    }
  }

  if (overview && scroller) {
    overview.style.cursor = 'crosshair';
    overview.addEventListener('click', function (event) {
      var box = overview.getBoundingClientRect();
      if (box.width <= 0) {
        return;
      }
      var fraction = (event.clientX - box.left) / box.width;
      scroller.scrollLeft = fraction * stripWidth() - scroller.clientWidth / 2;
    });
  }

  if (scroller) {
    scroller.addEventListener('click', function (event) {
      var target = event.target;
      var span = target && target.closest ? target.closest('.span') : null;
      var title = span ? span.querySelector('title') : null;
      say(title ? title.textContent : '');
    });
  }
}());
