package com.commit451.gitlab.adapter

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.commit451.gitlab.R
import com.commit451.gitlab.fragment.PipelineDescriptionFragment
import com.commit451.gitlab.model.api.Pipeline
import com.commit451.gitlab.model.api.Project

/**
 * Pipeline sections
 */
class PipelinePagerAdapter(context: Context, fm: androidx.fragment.app.FragmentManager, private val project: Project, private val pipeline: Pipeline) : androidx.fragment.app.FragmentPagerAdapter(fm) {
    private val titles: Array<String> = context.resources.getStringArray(R.array.pipeline_tabs)

    override fun getItem(position: Int): androidx.fragment.app.Fragment {

        when (position) {
            0 -> return PipelineDescriptionFragment.newInstance(project, pipeline)
        //1 -> return PipelineDescriptionFragment.newInstance(project, pipeline)
        }

        throw IllegalStateException("Position exceeded on view pager")
    }

    override fun getCount(): Int {
        return titles.size
    }

    override fun getPageTitle(position: Int): CharSequence {
        return titles[position]
    }
}
