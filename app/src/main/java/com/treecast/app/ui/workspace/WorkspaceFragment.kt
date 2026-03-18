package com.treecast.app.ui.workspace

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.treecast.app.R

/**
 * Workspace tab — placeholder fragment for far-future dev work.
 *
 * Currently displays a rainbow arc in the face-position of a portrait frame.
 * Visibility of the corresponding nav tab is gated behind the "Future Mode"
 * toggle in Settings → Dev Options.
 */
class WorkspaceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_workspace, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Size the portrait card to 80 % of screen width × 4:3 aspect ratio.
        // We do this here rather than in XML because the aspect ratio depends
        // on the actual measured screen width.
        view.post {
            val card = view.findViewById<View>(R.id.portraitCard) ?: return@post
            val screenW = view.width
            val cardW   = (screenW * 0.80f).toInt()
            val cardH   = (cardW * 1.333f).toInt()   // 3:4 portrait ratio
            card.layoutParams = (card.layoutParams as ViewGroup.LayoutParams).also {
                it.width  = cardW
                it.height = cardH
            }
        }
    }
}