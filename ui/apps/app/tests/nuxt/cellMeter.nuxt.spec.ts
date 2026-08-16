import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CellMeter from '#design/components/CellMeter.vue'
import PipelineRail from '#design/components/PipelineRail.vue'
import type { PipelineStage } from '#core/utils/pipeline'

describe('CellMeter', () => {
  it('exposes the percentage to assistive technology and to the eye', async () => {
    const wrapper = await mountSuspended(CellMeter, {
      props: { percent: 42, label: 'Render progress' }
    })

    const bar = wrapper.find('[role="progressbar"]')
    expect(bar.attributes('aria-valuenow')).toBe('42')
    expect(bar.attributes('aria-label')).toBe('Render progress')
    // Never colour or cell count alone.
    expect(wrapper.text()).toContain('42%')
  })

  it('leaves the last cell unfilled until the work is actually complete', async () => {
    const wrapper = await mountSuspended(CellMeter, {
      props: { percent: 99.6, cells: 10, label: 'Render progress' }
    })

    const empty = wrapper.findAll('.apus-cell').filter(cell => cell.classes('apus-cell--empty'))
    expect(empty).toHaveLength(1)
  })

  it('does not print 100% beside a cell it left empty', async () => {
    // Caught by looking at the thing: the cells honoured "not finished" and the readout rounded
    // 99.6 to 100 anyway, so the meter argued with itself and one half was lying.
    const wrapper = await mountSuspended(CellMeter, {
      props: { percent: 99.6, cells: 10, label: 'Render progress' }
    })

    expect(wrapper.text()).toContain('99%')
    expect(wrapper.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('99')
  })

  it('pulses nothing when the work is not live', async () => {
    const wrapper = await mountSuspended(CellMeter, {
      props: { percent: 40, cells: 10, label: 'Render progress', live: false }
    })

    expect(wrapper.findAll('.apus-cell--live')).toHaveLength(0)
  })
})

function stage(key: PipelineStage['key'], state: PipelineStage['state'], percent = 0): PipelineStage {
  return { key, label: key, state, percent, detail: `${key} detail` }
}

describe('PipelineRail', () => {
  it('names every stage state in words, not only in colour', async () => {
    const wrapper = await mountSuspended(PipelineRail, {
      props: {
        stages: [
          stage('source', 'done'),
          stage('bundle', 'pending'),
          stage('map', 'done'),
          stage('render', 'failed'),
          stage('hosting', 'skipped')
        ]
      }
    })

    expect(wrapper.text()).toContain('Done')
    expect(wrapper.text()).toContain('Waiting')
    expect(wrapper.text()).toContain('Failed')
    expect(wrapper.text()).toContain('Not applicable')
  })

  it('keeps the state readable in the compact variant, where there is no room to print it', async () => {
    const wrapper = await mountSuspended(PipelineRail, {
      props: { compact: true, stages: [stage('render', 'failed')] }
    })

    // The squares are aria-hidden, so without this the compact row would be invisible to a
    // screen reader -- exactly the case where colour-only signalling usually creeps in.
    expect(wrapper.find('.sr-only').text()).toContain('Failed')
  })

  it('shows a meter only for the stage that is actually running', async () => {
    const wrapper = await mountSuspended(PipelineRail, {
      props: { stages: [stage('source', 'done'), stage('render', 'active', 30)] }
    })

    const bars = wrapper.findAll('[role="progressbar"]')
    expect(bars).toHaveLength(1)
    expect(bars[0]!.attributes('aria-label')).toBe('render progress')
  })
})
