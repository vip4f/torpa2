/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 1/17/25.
 */


package llmtest


import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal


package object torpa2 {

  // See https://github.com/markedjs/marked
  @js.native
  @JSGlobal
  object marked extends js.Object {
    def parse(text: String): String = js.native
  } 
  
}
