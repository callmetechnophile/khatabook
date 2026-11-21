/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Implementation file for embedded RNNoise neural network model arrays.
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include "rnnoise_data.h"
#include <string.h>

#ifndef USE_WEIGHTS_FILE
const WeightArray rnnoise_arrays[] = {
    {NULL, 0, 0, NULL}
};
#endif

/*
 * Initialize model layers with internal dimensions.
 * Parameter model: Target RNNoise structure.
 * Parameter arrays: Array collection (unused when using embedded structures).
 * Returns 0 on success.
 */
int init_rnnoise(RNNoise *model, const WeightArray *arrays) {
    (void)arrays;
    if (model == NULL) {
        return 1;
    }
    memset(model, 0, sizeof(*model));

    model->conv1.nb_inputs = 3 * 42;
    model->conv1.nb_outputs = 128;

    model->conv2.nb_inputs = 3 * 128;
    model->conv2.nb_outputs = 256;

    model->gru1_input.nb_inputs = 256;
    model->gru1_input.nb_outputs = 3 * 256;
    model->gru1_recurrent.nb_inputs = 256;
    model->gru1_recurrent.nb_outputs = 3 * 256;

    model->gru2_input.nb_inputs = 256;
    model->gru2_input.nb_outputs = 3 * 256;
    model->gru2_recurrent.nb_inputs = 256;
    model->gru2_recurrent.nb_outputs = 3 * 256;

    model->gru3_input.nb_inputs = 256;
    model->gru3_input.nb_outputs = 3 * 256;
    model->gru3_recurrent.nb_inputs = 256;
    model->gru3_recurrent.nb_outputs = 3 * 256;

    model->dense_out.nb_inputs = 4 * 256;
    model->dense_out.nb_outputs = 32;

    model->vad_dense.nb_inputs = 4 * 256;
    model->vad_dense.nb_outputs = 1;

    return 0;
}
