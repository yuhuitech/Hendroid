package me.devsaki.hentoid.adapters;

import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import me.devsaki.hentoid.R;
import me.devsaki.hentoid.util.ImageLoaderThreadExecutor;
import me.devsaki.hentoid.util.Preferences;
import timber.log.Timber;


public final class ImageRecyclerAdapter extends RecyclerView.Adapter<ImageRecyclerAdapter.ImageViewHolder> {

    // TODO : SubsamplingScaleImageView does _not_ support animated GIFs -> use pl.droidsonroids.gif:android-gif-drawable when serving a GIF ?

    private static final Executor executor = new ImageLoaderThreadExecutor();


    private View.OnTouchListener itemTouchListener;

    private List<String> imageUris;


    @Override
    public int getItemCount() {
        return imageUris.size();
    }

    public void setImageUris(List<String> imageUris) {
        this.imageUris = Collections.unmodifiableList(imageUris);
    }

    public void setItemTouchListener(View.OnTouchListener itemTouchListener) {
        this.itemTouchListener = itemTouchListener;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        View view = inflater.inflate(R.layout.item_viewer_image, viewGroup, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder viewHolder, int pos) {
        viewHolder.setImageUri(imageUris.get(pos));

        int layoutStyle = (Preferences.Constant.PREF_VIEWER_ORIENTATION_VERTICAL == Preferences.getViewerOrientation()) ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;

        ViewGroup.LayoutParams layoutParams = viewHolder.imgView.getLayoutParams();
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = layoutStyle;
        viewHolder.imgView.setLayoutParams(layoutParams);
    }

    final class ImageViewHolder extends RecyclerView.ViewHolder {

        private final SubsamplingScaleImageView imgView;

        private ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imgView = (SubsamplingScaleImageView) itemView;
            imgView.setExecutor(executor);
            imgView.setOnTouchListener(itemTouchListener);
        }

        void setImageUri(String uri) {
            imgView.recycle();
            imgView.setMinimumScaleType(getScaleType());
Timber.i(">>>>IMG %s", uri);
            imgView.setImage(ImageSource.uri(uri));
        }

        private int getScaleType() {
            if (Preferences.Constant.PREF_VIEWER_DISPLAY_FILL == Preferences.getViewerResizeMode()) {
                return SubsamplingScaleImageView.SCALE_TYPE_START;
            } else {
                return SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE;
            }
        }
    }
}