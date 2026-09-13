package com.theveloper.pixelplay.data.tais.stems

import java.io.File

/** A cloud-rendered stem separation result, shared by every hosted-backend client (Gradio, direct POST, ...). */
data class RoformerResult(val instrumentalFile: File, val vocalsFile: File?)
